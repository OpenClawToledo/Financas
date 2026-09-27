-- =====================================================================
--  Finanças — esquema do Supabase
--  Cole tudo no Supabase: SQL Editor → New query → Run.
--  Pode rodar de novo sem problema: só cria o que ainda não existe.
-- =====================================================================

-- Casal: dois usuários ligados por um código de convite
create table if not exists public.casais (
  id         uuid primary key default gen_random_uuid(),
  codigo     text unique not null,
  criado_em  timestamptz not null default now()
);

create table if not exists public.membros (
  casal_id  uuid not null references public.casais(id) on delete cascade,
  user_id   uuid not null references auth.users(id) on delete cascade,
  nome      text not null default '',
  entrou_em timestamptz not null default now(),
  primary key (casal_id, user_id)
);
-- cada pessoa está em no máximo um casal
create unique index if not exists membros_um_casal on public.membros(user_id);

-- Tudo o que o app guarda: lançamentos, contas, metas, trocas, curtidas, comentários...
create table if not exists public.registros (
  id            uuid primary key,
  dono          uuid not null default auth.uid() references auth.users(id) on delete cascade,
  casal_id      uuid references public.casais(id) on delete set null,
  tipo          text not null,
  compartilhado boolean not null default false,
  dados         jsonb not null default '{}'::jsonb,
  apagado       boolean not null default false,
  atualizado    timestamptz not null default now()
);
create index if not exists registros_atualizado on public.registros(atualizado);
create index if not exists registros_casal on public.registros(casal_id) where compartilhado;

-- Casal de quem está chamando (security definer evita recursão nas regras de acesso)
create or replace function public.meu_casal() returns uuid
language sql stable security definer set search_path = public as $$
  select casal_id from public.membros where user_id = auth.uid() limit 1
$$;

-- Em toda gravação: carimba a hora do servidor, fixa o dono e liga ao casal atual
create or replace function public.registros_carimbar() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' then
    new.dono := old.dono;               -- o dono nunca muda
  end if;
  new.atualizado := clock_timestamp();
  new.casal_id := (select casal_id from public.membros where user_id = new.dono limit 1);
  return new;
end $$;

drop trigger if exists registros_carimbar on public.registros;
create trigger registros_carimbar before insert or update on public.registros
  for each row execute function public.registros_carimbar();

-- ---------------------------------------------------------------------
--  Regras de acesso: cada um vê o que é seu e o que o casal compartilhou
-- ---------------------------------------------------------------------
alter table public.casais    enable row level security;
alter table public.membros   enable row level security;
alter table public.registros enable row level security;

drop policy if exists casais_ver on public.casais;
create policy casais_ver on public.casais for select to authenticated
  using (id = public.meu_casal());

drop policy if exists membros_ver on public.membros;
create policy membros_ver on public.membros for select to authenticated
  using (casal_id = public.meu_casal());

drop policy if exists membros_nome on public.membros;
create policy membros_nome on public.membros for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists registros_ver on public.registros;
create policy registros_ver on public.registros for select to authenticated
  using (dono = auth.uid() or (compartilhado and casal_id is not null and casal_id = public.meu_casal()));

drop policy if exists registros_criar on public.registros;
create policy registros_criar on public.registros for insert to authenticated
  with check (dono = auth.uid());

drop policy if exists registros_alterar on public.registros;
create policy registros_alterar on public.registros for update to authenticated
  using (dono = auth.uid()) with check (dono = auth.uid());
-- sem regra de delete: o app só marca "apagado", para o outro aparelho saber

-- ---------------------------------------------------------------------
--  Funções do casal (chamadas pelo app)
-- ---------------------------------------------------------------------

-- Cria o casal (ou devolve o código do casal atual)
create or replace function public.criar_casal(p_nome text) returns text
language plpgsql security definer set search_path = public as $$
declare cid uuid; cod text;
begin
  if auth.uid() is null then raise exception 'Faça login primeiro'; end if;
  select c.id, c.codigo into cid, cod from public.casais c join public.membros m on m.casal_id = c.id where m.user_id = auth.uid();
  if cid is not null then return cod; end if;
  loop
    cod := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
    exit when not exists (select 1 from public.casais where codigo = cod);
  end loop;
  insert into public.casais(codigo) values (cod) returning id into cid;
  insert into public.membros(casal_id, user_id, nome) values (cid, auth.uid(), coalesce(p_nome, ''));
  update public.registros set atualizado = clock_timestamp() where dono = auth.uid();  -- religa ao casal
  return cod;
end $$;

-- Entra no casal com o código recebido
create or replace function public.entrar_casal(p_codigo text, p_nome text) returns text
language plpgsql security definer set search_path = public as $$
declare cid uuid;
begin
  if auth.uid() is null then raise exception 'Faça login primeiro'; end if;
  select id into cid from public.casais where codigo = upper(trim(p_codigo));
  if cid is null then return 'codigo_invalido'; end if;
  if exists (select 1 from public.membros where user_id = auth.uid()) then
    if exists (select 1 from public.membros where user_id = auth.uid() and casal_id = cid) then return 'ok'; end if;
    return 'ja_em_casal';
  end if;
  if (select count(*) from public.membros where casal_id = cid) >= 2 then return 'casal_completo'; end if;
  insert into public.membros(casal_id, user_id, nome) values (cid, auth.uid(), coalesce(p_nome, ''));
  update public.registros set atualizado = clock_timestamp() where dono = auth.uid();
  -- o parceiro precisa receber de novo o que já tinha compartilhado
  update public.registros set atualizado = clock_timestamp() where casal_id = cid and compartilhado;
  return 'ok';
end $$;

-- Sai do casal: o que era compartilhado deixa de aparecer para o outro
create or replace function public.sair_casal() returns void
language plpgsql security definer set search_path = public as $$
declare cid uuid;
begin
  select casal_id into cid from public.membros where user_id = auth.uid();
  if cid is null then return; end if;
  delete from public.membros where user_id = auth.uid();
  update public.registros set atualizado = clock_timestamp() where dono = auth.uid();
  delete from public.casais c where c.id = cid and not exists (select 1 from public.membros where casal_id = cid);
end $$;

-- Atualiza o nome mostrado ao parceiro
create or replace function public.definir_nome(p_nome text) returns void
language sql security definer set search_path = public as $$
  update public.membros set nome = coalesce(p_nome, '') where user_id = auth.uid()
$$;

revoke all on function public.criar_casal(text), public.entrar_casal(text, text), public.sair_casal(), public.definir_nome(text) from public, anon;
grant execute on function public.criar_casal(text), public.entrar_casal(text, text), public.sair_casal(), public.definir_nome(text), public.meu_casal() to authenticated;
grant select, insert, update on public.registros to authenticated;
grant select on public.casais, public.membros to authenticated;
grant update (nome) on public.membros to authenticated;
