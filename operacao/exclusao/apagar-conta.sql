\set ON_ERROR_STOP on

-- O serviço fica sem tráfego durante este procedimento. A tabela temporária evita aceitar um
-- conta_id novo em cada DELETE e mantém todas as remoções na mesma transação.
BEGIN;
CREATE TEMP TABLE conta_encerrada (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO conta_encerrada SELECT id FROM conta WHERE id = :'conta_id'::uuid FOR UPDATE;

DELETE FROM event_publication e WHERE EXISTS (
    SELECT 1 FROM conta_encerrada c WHERE position(c.id::text in e.serialized_event) > 0
);
DELETE FROM operacao_sincronizada WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM movimento_caixa WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM recebimento WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM movimento_estoque WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM pagamento WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM item_venda WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM venda WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM sessao_caixa WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM produto WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM cliente WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM credencial WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM usuario WHERE conta_id IN (SELECT id FROM conta_encerrada);
DELETE FROM conta WHERE id IN (SELECT id FROM conta_encerrada);
COMMIT;
