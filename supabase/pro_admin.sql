-- =====================================================================
-- APP FINANÇAS — Pro por assinatura + painel admin + config do servidor
-- Onde colar: Supabase > SQL Editor > New query > Run (depois do esquema.sql)
-- Pode rodar de novo sem quebrar (usa IF NOT EXISTS / OR REPLACE).
-- Desativar/expirar o Pro NUNCA apaga dados: só muda a data de validade.
-- =====================================================================

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------
-- 1. PERFIS  (is_admin só muda pelo SQL Editor, nunca pelo app)
-- ---------------------------------------------------------------------
create table if not exists public.perfis (
  id         uuid primary key references auth.users(id) on delete cascade,
  email      text,
  nome       text,
  is_admin   boolean not null default false,
  criado_em  timestamptz not null default now()
);
alter table public.perfis add column if not exists email    text;
alter table public.perfis add column if not exists nome     text;
alter table public.perfis add column if not exists is_admin boolean not null default false;

alter table public.perfis enable row level security;

-- O app só pode editar o próprio "nome". is_admin fica bloqueado por permissão de coluna.
revoke insert, update, delete on public.perfis from anon, authenticated;
grant  select       on public.perfis to authenticated;
grant  update (nome) on public.perfis to authenticated;

create or replace function public.is_admin()
returns boolean
language sql stable security definer set search_path = ''
as $$
  select coalesce((select p.is_admin from public.perfis p where p.id = auth.uid()), false);
$$;

drop policy if exists perfis_ler on public.perfis;
create policy perfis_ler on public.perfis
  for select to authenticated
  using (id = auth.uid() or public.is_admin());

drop policy if exists perfis_editar_nome on public.perfis;
create policy perfis_editar_nome on public.perfis
  for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- Cria o perfil automaticamente quando alguém cria conta
create or replace function public.criar_perfil()
returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
  insert into public.perfis (id, email) values (new.id, new.email)
  on conflict (id) do update set email = excluded.email;
  return new;
end;
$$;

drop trigger if exists ao_criar_usuario on auth.users;
create trigger ao_criar_usuario
  after insert on auth.users
  for each row execute function public.criar_perfil();

-- Perfis para contas que já existem
insert into public.perfis (id, email)
select u.id, u.email from auth.users u
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- 2. ASSINATURAS  (uma linha por usuário; Pro ativo = expira_em > agora)
-- ---------------------------------------------------------------------
create table if not exists public.assinaturas (
  user_id        uuid primary key references auth.users(id) on delete cascade,
  plano          text not null default 'pro',
  expira_em      timestamptz not null,
  origem         text,            -- 'pedido' | 'codigo' | 'manual'
  concedido_por  uuid references auth.users(id),
  atualizado_em  timestamptz not null default now()
);

alter table public.assinaturas enable row level security;
revoke insert, update, delete on public.assinaturas from anon, authenticated;
grant  select on public.assinaturas to authenticated;

drop policy if exists assinaturas_ler on public.assinaturas;
create policy assinaturas_ler on public.assinaturas
  for select to authenticated
  using (user_id = auth.uid() or public.is_admin());

-- Use em políticas de outras tabelas para liberar recursos Pro
create or replace function public.tem_pro()
returns boolean
language sql stable security definer set search_path = ''
as $$
  select exists (
    select 1 from public.assinaturas a
    where a.user_id = auth.uid() and a.expira_em > now()
  );
$$;

-- Interno: soma dias a partir de hoje ou do fim da assinatura atual (o que for maior)
create or replace function public._somar_dias(p_user uuid, p_dias int, p_origem text)
returns timestamptz
language plpgsql security definer set search_path = ''
as $$
declare
  v_expira timestamptz;
begin
  insert into public.assinaturas as a (user_id, expira_em, origem, concedido_por, atualizado_em)
  values (p_user, now() + make_interval(days => p_dias), p_origem, auth.uid(), now())
  on conflict (user_id) do update
    set expira_em     = greatest(a.expira_em, now()) + make_interval(days => p_dias),
        origem        = excluded.origem,
        concedido_por = excluded.concedido_por,
        atualizado_em = now()
  returning a.expira_em into v_expira;
  return v_expira;
end;
$$;

-- ---------------------------------------------------------------------
-- 3. PEDIDOS PRO
-- ---------------------------------------------------------------------
create table if not exists public.pedidos_pro (
  id               bigint generated always as identity primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  mensagem         text check (char_length(mensagem) <= 500),
  dias_pedidos     int  check (dias_pedidos between 1 and 3650),
  status           text not null default 'pendente'
                   check (status in ('pendente','aprovado','recusado','cancelado')),
  dias_concedidos  int,
  resposta         text,
  criado_em        timestamptz not null default now(),
  respondido_em    timestamptz,
  respondido_por   uuid references auth.users(id)
);

-- No máximo 1 pedido pendente por pessoa
create unique index if not exists pedidos_um_pendente
  on public.pedidos_pro (user_id) where status = 'pendente';

alter table public.pedidos_pro enable row level security;
revoke insert, update, delete on public.pedidos_pro from anon, authenticated;
grant  select on public.pedidos_pro to authenticated;

drop policy if exists pedidos_ler on public.pedidos_pro;
create policy pedidos_ler on public.pedidos_pro
  for select to authenticated
  using (user_id = auth.uid() or public.is_admin());

-- ---------------------------------------------------------------------
-- 4. CÓDIGOS DE ATIVAÇÃO (opcional: para mandar por WhatsApp etc.)
--    Só o hash é guardado; o código em si aparece uma única vez para você.
-- ---------------------------------------------------------------------
create table if not exists public.codigos_ativacao (
  id           bigint generated always as identity primary key,
  codigo_hash  text not null unique,
  dias         int  not null check (dias between 1 and 3650),
  observacao   text,
  valido_ate   timestamptz,        -- prazo para resgatar
  criado_por   uuid references auth.users(id),
  criado_em    timestamptz not null default now(),
  usado_por    uuid references auth.users(id),
  usado_em     timestamptz
);

alter table public.codigos_ativacao enable row level security;
revoke all    on public.codigos_ativacao from anon, authenticated;
grant  select on public.codigos_ativacao to authenticated;

drop policy if exists codigos_admin_ler on public.codigos_ativacao;
create policy codigos_admin_ler on public.codigos_ativacao
  for select to authenticated
  using (public.is_admin());

-- ---------------------------------------------------------------------
-- 5. CONFIG DO APP  (todos leem, só admin altera)
-- ---------------------------------------------------------------------
create table if not exists public.config_app (
  chave           text primary key,
  valor           jsonb not null,
  descricao       text,
  atualizado_em   timestamptz not null default now(),
  atualizado_por  uuid references auth.users(id)
);

alter table public.config_app enable row level security;
revoke all on public.config_app from anon, authenticated;
grant  select                         on public.config_app to anon;
grant  select, insert, update, delete on public.config_app to authenticated;

drop policy if exists config_ler on public.config_app;
create policy config_ler on public.config_app
  for select to anon, authenticated using (true);

drop policy if exists config_admin_inserir on public.config_app;
create policy config_admin_inserir on public.config_app
  for insert to authenticated with check (public.is_admin());

drop policy if exists config_admin_alterar on public.config_app;
create policy config_admin_alterar on public.config_app
  for update to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists config_admin_apagar on public.config_app;
create policy config_admin_apagar on public.config_app
  for delete to authenticated using (public.is_admin());

create or replace function public.config_carimbo()
returns trigger
language plpgsql set search_path = ''
as $$
begin
  new.atualizado_em  := now();
  new.atualizado_por := auth.uid();
  return new;
end;
$$;

drop trigger if exists config_carimbo on public.config_app;
create trigger config_carimbo
  before insert or update on public.config_app
  for each row execute function public.config_carimbo();

insert into public.config_app (chave, valor, descricao) values
  ('versao_minima', '1',
   'versionCode mínimo aceito; abaixo disso o app obriga a atualizar'),
  ('versao_atual',  '{"versionCode": 1, "versionName": "1.0.0", "apk_url": "", "notas": ""}',
   'Última versão publicada; o app compara e oferece atualização'),
  ('aviso',         '{"ativo": false, "texto": ""}',
   'Aviso exibido a todos os usuários'),
  ('pro',           '{"aceitando_pedidos": true, "opcoes_dias": [30, 90, 365], "texto_pagamento": "Combine o pagamento comigo."}',
   'Regras e textos do plano Pro')
on conflict (chave) do nothing;

-- ---------------------------------------------------------------------
-- 6. FUNÇÕES DO USUÁRIO (chamadas pelo app)
-- ---------------------------------------------------------------------

-- Status completo numa chamada só
create or replace function public.meu_status_pro()
returns table (tem_pro boolean, expira_em timestamptz, pedido_pendente boolean, is_admin boolean)
language sql stable security definer set search_path = ''
as $$
  select
    coalesce(a.expira_em > now(), false),
    a.expira_em,
    exists (select 1 from public.pedidos_pro p
            where p.user_id = auth.uid() and p.status = 'pendente'),
    public.is_admin()
  from (select 1) x
  left join public.assinaturas a on a.user_id = auth.uid();
$$;

create or replace function public.pedir_pro(p_mensagem text default null, p_dias int default 30)
returns bigint
language plpgsql security definer set search_path = ''
as $$
declare
  v_id     bigint;
  v_aberto boolean;
begin
  if auth.uid() is null then
    raise exception 'Faça login primeiro';
  end if;

  select coalesce((c.valor->>'aceitando_pedidos')::boolean, true)
    into v_aberto
    from public.config_app c where c.chave = 'pro';
  if v_aberto is false then
    raise exception 'Pedidos Pro fechados no momento';
  end if;

  insert into public.pedidos_pro (user_id, mensagem, dias_pedidos)
  values (auth.uid(), left(p_mensagem, 500), p_dias)
  returning id into v_id;
  return v_id;
exception
  when unique_violation then
    raise exception 'Você já tem um pedido pendente';
end;
$$;

create or replace function public.cancelar_meu_pedido()
returns void
language sql security definer set search_path = ''
as $$
  update public.pedidos_pro
     set status = 'cancelado', respondido_em = now()
   where user_id = auth.uid() and status = 'pendente';
$$;

create or replace function public.resgatar_codigo(p_codigo text)
returns timestamptz
language plpgsql security definer set search_path = ''
as $$
declare
  v_limpo text;
  v_dias  int;
begin
  if auth.uid() is null then
    raise exception 'Faça login primeiro';
  end if;

  -- aceita com ou sem traços, maiúsculas ou minúsculas
  v_limpo := upper(regexp_replace(coalesce(p_codigo, ''), '[^0-9A-Fa-f]', '', 'g'));
  if length(v_limpo) <> 12 then
    raise exception 'Código inválido';
  end if;
  v_limpo := substr(v_limpo, 1, 4) || '-' || substr(v_limpo, 5, 4) || '-' || substr(v_limpo, 9, 4);

  update public.codigos_ativacao
     set usado_por = auth.uid(), usado_em = now()
   where codigo_hash = encode(extensions.digest(v_limpo, 'sha256'), 'hex')
     and usado_por is null
     and (valido_ate is null or valido_ate > now())
  returning dias into v_dias;

  if v_dias is null then
    raise exception 'Código inválido, expirado ou já usado';
  end if;

  return public._somar_dias(auth.uid(), v_dias, 'codigo');
end;
$$;

-- ---------------------------------------------------------------------
-- 7. FUNÇÕES DO ADMIN (só funcionam se is_admin = true)
-- ---------------------------------------------------------------------
create or replace function public.admin_listar_pedidos(p_status text default 'pendente')
returns table (
  pedido_id bigint, user_id uuid, email text, nome text, mensagem text,
  dias_pedidos int, status text, criado_em timestamptz, pro_expira_em timestamptz
)
language plpgsql stable security definer set search_path = ''
as $$
#variable_conflict use_column
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  return query
    select p.id, p.user_id, pf.email, pf.nome, p.mensagem,
           p.dias_pedidos, p.status, p.criado_em, a.expira_em
      from public.pedidos_pro p
      left join public.perfis      pf on pf.id    = p.user_id
      left join public.assinaturas a  on a.user_id = p.user_id
     where p_status is null or p.status = p_status
     order by p.criado_em desc;
end;
$$;

create or replace function public.admin_listar_usuarios()
returns table (user_id uuid, email text, nome text, criado_em timestamptz,
               pro_expira_em timestamptz, pro_ativo boolean)
language plpgsql stable security definer set search_path = ''
as $$
#variable_conflict use_column
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  return query
    select pf.id, pf.email, pf.nome, pf.criado_em, a.expira_em,
           coalesce(a.expira_em > now(), false)
      from public.perfis pf
      left join public.assinaturas a on a.user_id = pf.id
     order by pf.criado_em desc;
end;
$$;

create or replace function public.aprovar_pedido(p_pedido_id bigint, p_dias int, p_resposta text default null)
returns timestamptz
language plpgsql security definer set search_path = ''
as $$
declare
  v_user uuid;
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  if p_dias is null or p_dias < 1 or p_dias > 3650 then
    raise exception 'Quantidade de dias inválida';
  end if;

  update public.pedidos_pro
     set status = 'aprovado', dias_concedidos = p_dias, resposta = p_resposta,
         respondido_em = now(), respondido_por = auth.uid()
   where id = p_pedido_id and status = 'pendente'
  returning user_id into v_user;

  if v_user is null then
    raise exception 'Pedido não encontrado ou já respondido';
  end if;

  return public._somar_dias(v_user, p_dias, 'pedido');
end;
$$;

create or replace function public.recusar_pedido(p_pedido_id bigint, p_resposta text default null)
returns void
language plpgsql security definer set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  update public.pedidos_pro
     set status = 'recusado', resposta = p_resposta,
         respondido_em = now(), respondido_por = auth.uid()
   where id = p_pedido_id and status = 'pendente';
  if not found then
    raise exception 'Pedido não encontrado ou já respondido';
  end if;
end;
$$;

-- Dar dias direto, sem pedido (ex.: sua esposa)
create or replace function public.admin_conceder_dias(p_user uuid, p_dias int)
returns timestamptz
language plpgsql security definer set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  if p_dias is null or p_dias < 1 or p_dias > 36500 then
    raise exception 'Quantidade de dias inválida';
  end if;
  return public._somar_dias(p_user, p_dias, 'manual');
end;
$$;

-- Encerra o Pro agora. Os dados do usuário continuam intactos.
create or replace function public.admin_revogar_pro(p_user uuid)
returns void
language plpgsql security definer set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  update public.assinaturas
     set expira_em = now(), origem = 'manual',
         concedido_por = auth.uid(), atualizado_em = now()
   where user_id = p_user;
end;
$$;

-- Gera um código tipo "A1B2-C3D4-E5F6". Anote: ele só aparece aqui.
create or replace function public.admin_gerar_codigo(
  p_dias int, p_observacao text default null, p_validade_dias int default 30
)
returns text
language plpgsql security definer set search_path = ''
as $$
declare
  v_codigo text;
begin
  if not public.is_admin() then
    raise exception 'Apenas admin';
  end if;
  if p_dias is null or p_dias < 1 or p_dias > 3650 then
    raise exception 'Quantidade de dias inválida';
  end if;

  v_codigo := upper(encode(extensions.gen_random_bytes(6), 'hex'));
  v_codigo := substr(v_codigo, 1, 4) || '-' || substr(v_codigo, 5, 4) || '-' || substr(v_codigo, 9, 4);

  insert into public.codigos_ativacao (codigo_hash, dias, observacao, valido_ate, criado_por)
  values (
    encode(extensions.digest(v_codigo, 'sha256'), 'hex'),
    p_dias, p_observacao,
    case when p_validade_dias is null then null
         else now() + make_interval(days => p_validade_dias) end,
    auth.uid()
  );
  return v_codigo;
end;
$$;

-- ---------------------------------------------------------------------
-- 8. PERMISSÕES DAS FUNÇÕES
-- ---------------------------------------------------------------------
-- Internas: ninguém chama pelo app
revoke execute on function public._somar_dias(uuid, int, text) from public, anon, authenticated;
revoke execute on function public.criar_perfil()               from public, anon, authenticated;
revoke execute on function public.config_carimbo()             from public, anon, authenticated;

-- Só para quem está logado (as de admin ainda checam is_admin por dentro)
revoke execute on function public.is_admin()                               from public, anon;
revoke execute on function public.tem_pro()                                from public, anon;
revoke execute on function public.meu_status_pro()                         from public, anon;
revoke execute on function public.pedir_pro(text, int)                     from public, anon;
revoke execute on function public.cancelar_meu_pedido()                    from public, anon;
revoke execute on function public.resgatar_codigo(text)                    from public, anon;
revoke execute on function public.admin_listar_pedidos(text)               from public, anon;
revoke execute on function public.admin_listar_usuarios()                  from public, anon;
revoke execute on function public.aprovar_pedido(bigint, int, text)        from public, anon;
revoke execute on function public.recusar_pedido(bigint, text)             from public, anon;
revoke execute on function public.admin_conceder_dias(uuid, int)           from public, anon;
revoke execute on function public.admin_revogar_pro(uuid)                  from public, anon;
revoke execute on function public.admin_gerar_codigo(int, text, int)       from public, anon;

grant execute on function public.is_admin()                                to authenticated;
grant execute on function public.tem_pro()                                 to authenticated;
grant execute on function public.meu_status_pro()                          to authenticated;
grant execute on function public.pedir_pro(text, int)                      to authenticated;
grant execute on function public.cancelar_meu_pedido()                     to authenticated;
grant execute on function public.resgatar_codigo(text)                     to authenticated;
grant execute on function public.admin_listar_pedidos(text)                to authenticated;
grant execute on function public.admin_listar_usuarios()                   to authenticated;
grant execute on function public.aprovar_pedido(bigint, int, text)         to authenticated;
grant execute on function public.recusar_pedido(bigint, text)              to authenticated;
grant execute on function public.admin_conceder_dias(uuid, int)            to authenticated;
grant execute on function public.admin_revogar_pro(uuid)                   to authenticated;
grant execute on function public.admin_gerar_codigo(int, text, int)        to authenticated;

-- ---------------------------------------------------------------------
-- 9. VIRAR ADMIN  (troque o e-mail e rode; precisa já ter criado a conta)
-- ---------------------------------------------------------------------
update public.perfis set is_admin = true where email = 'toledothelast@gmail.com';

-- ---------------------------------------------------------------------
-- EXEMPLO: travar um recurso Pro (contas recorrentes) sem apagar nada.
-- Ler continua liberado mesmo com o Pro vencido; criar/editar exige Pro.
-- Adapte ao nome real da sua tabela:
--
-- create policy recorrentes_criar on public.contas_recorrentes
--   for insert to authenticated
--   with check (user_id = auth.uid() and public.tem_pro());
-- create policy recorrentes_editar on public.contas_recorrentes
--   for update to authenticated
--   using (user_id = auth.uid() and public.tem_pro());
-- ---------------------------------------------------------------------
