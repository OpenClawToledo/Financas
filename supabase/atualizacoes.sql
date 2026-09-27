-- Pasta pública para as atualizações automáticas do app.
-- Qualquer um pode BAIXAR; só quem tem a chave secreta (o GitHub) pode PUBLICAR.
insert into storage.buckets (id, name, public)
values ('atualizacoes', 'atualizacoes', true)
on conflict (id) do update set public = true;
