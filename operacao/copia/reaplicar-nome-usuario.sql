\set ON_ERROR_STOP on

BEGIN;
DELETE FROM credencial
WHERE conta_id = :'conta_id'::uuid AND usuario_id = :'registro_id'::uuid;
UPDATE usuario SET nome = 'Usuário removido', ativo = false
WHERE conta_id = :'conta_id'::uuid AND id = :'registro_id'::uuid;
COMMIT;
