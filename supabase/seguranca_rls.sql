-- ════════════════════════════════════════════════════════════════════
-- ImpulsoTV — Endurecimento do acesso anônimo (Fase 3)
-- Rodar no Supabase → SQL Editor, UMA ETAPA POR VEZ, na ordem.
--
-- Auditoria de 17/11/2026 com a chave pública (a mesma que vai no APK):
--   • LEITURA liberada: devices (8 linhas, inclui device_code), playlist_items,
--     media_files, playlists
--   • ALTERAR/APAGAR aceitos pelo banco (HTTP 204) nas 4 tabelas
--
-- O que o app Android (3.2.x) usa com a chave pública:
--   • Edge Functions device-connect / device-heartbeat / device-api
--     → usam a chave de serviço, NÃO dependem destas permissões
--   • Realtime (postgres_changes) → precisa só de SELECT em devices e
--     playlist_items
-- O painel web deve usar usuários logados (papel "authenticated"),
-- que estas mudanças NÃO afetam.
-- ════════════════════════════════════════════════════════════════════


-- ── ETAPA 0 — Diagnóstico (só leitura). Guarde o resultado. ──────────
select tablename, policyname, roles, cmd, qual, with_check
from pg_policies
where schemaname = 'public'
order by tablename, cmd;

select table_name, privilege_type
from information_schema.role_table_grants
where table_schema = 'public' and grantee = 'anon'
order by table_name, privilege_type;


-- ── ETAPA 1 — Anônimo não altera nem apaga nada (recomendado) ─────────
-- Remove INSERT/UPDATE/DELETE do papel anon. A leitura continua (Realtime).
revoke insert, update, delete on public.devices        from anon;
revoke insert, update, delete on public.playlist_items from anon;
revoke insert, update, delete on public.media_files    from anon;
revoke insert, update, delete on public.playlists      from anon;

-- Depois desta etapa: confira que as TVs continuam tocando e que o painel
-- continua salvando playlists/dispositivos normalmente.


-- ── ETAPA 2 — Esconder códigos de pareamento (opcional, testar) ──────
-- O app/Realtime não precisa ler device_code nem connection_fingerprint
-- pela chave pública. Troca o SELECT da tabela inteira por colunas.
-- ⚠️ Só rode se houver um "player web" que NÃO use a chave pública para
--    ler devices; teste o Realtime de uma TV logo em seguida.
-- revoke select on public.devices from anon;
-- grant select (id, user_id, name, location, playlist_id, last_checkin,
--               is_online, created_at, updated_at, is_connected,
--               last_connected_at, connection_type, restart_requested_at)
--   on public.devices to anon;


-- ── DESFAZER (se algo do painel parar de funcionar) ───────────────────
-- grant insert, update, delete on public.devices        to anon;
-- grant insert, update, delete on public.playlist_items to anon;
-- grant insert, update, delete on public.media_files    to anon;
-- grant insert, update, delete on public.playlists      to anon;
-- grant select on public.devices to anon;                -- desfaz a etapa 2
