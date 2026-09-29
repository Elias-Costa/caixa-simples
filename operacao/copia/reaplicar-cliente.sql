\set ON_ERROR_STOP on

BEGIN;
UPDATE cliente SET nome = 'Cliente removido', contato = NULL, ativo = false,
    removido_em = COALESCE(removido_em, :'instante'::timestamptz)
WHERE conta_id = :'conta_id'::uuid AND id = :'registro_id'::uuid;

UPDATE operacao_sincronizada
SET payload = '{}'::jsonb,
    detalhe = CASE WHEN detalhe IS NULL THEN NULL ELSE 'cliente removido' END
WHERE conta_id = :'conta_id'::uuid AND registro_id = :'registro_id'::uuid
    AND tipo IN ('cliente.criar', 'cliente.editar');
COMMIT;
