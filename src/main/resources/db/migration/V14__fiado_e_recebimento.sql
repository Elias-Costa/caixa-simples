-- O fiado é parcela da Venda: conclui a venda, mas só é quitado por recebimentos.
ALTER TABLE pagamento DROP CONSTRAINT pagamento_forma_valida;
ALTER TABLE pagamento ADD CONSTRAINT pagamento_forma_valida
    CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO', 'FIADO'));

COMMENT ON COLUMN pagamento.status IS
    'PENDENTE em FIADO e em cobrança de Pix ainda sem confirmação; lançamentos manuais de dinheiro, Pix e cartão nascem CONFIRMADO.';

-- Cada recebimento é um membro imutável da Venda, sem repositório próprio.
CREATE TABLE recebimento (
    id                uuid          PRIMARY KEY,
    conta_id          uuid          NOT NULL REFERENCES conta (id),
    venda_id          uuid          NOT NULL REFERENCES venda (id),
    sessao_caixa_id   uuid          NOT NULL REFERENCES sessao_caixa (id),
    valor             numeric(12,2) NOT NULL,
    forma             varchar(20)   NOT NULL,
    criado_em         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT recebimento_valor_positivo CHECK (valor > 0),
    CONSTRAINT recebimento_forma_valida CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO'))
);

CREATE INDEX idx_recebimento_conta ON recebimento (conta_id);
CREATE INDEX idx_recebimento_venda ON recebimento (venda_id);

COMMENT ON TABLE recebimento IS
    'Entrada parcial ou integral de fiado, membro imutável do agregado Venda; sem repositório próprio.';
COMMENT ON COLUMN recebimento.sessao_caixa_id IS
    'Sessão aberta da pessoa que recebeu, que pode ser diferente da sessão da Venda.';

-- O dinheiro do fiado entra na gaveta quando recebido, não na conclusão da Venda.
ALTER TABLE movimento_caixa DROP CONSTRAINT movimento_caixa_tipo_valido;
ALTER TABLE movimento_caixa ADD CONSTRAINT movimento_caixa_tipo_valido
    CHECK (tipo IN ('VENDA', 'SANGRIA', 'SUPRIMENTO', 'ESTORNO', 'RECEBIMENTO'));

ALTER TABLE movimento_caixa DROP CONSTRAINT movimento_caixa_motivo_obrigatorio;
ALTER TABLE movimento_caixa ADD CONSTRAINT movimento_caixa_motivo_obrigatorio
    CHECK (tipo IN ('VENDA', 'ESTORNO', 'RECEBIMENTO') OR motivo IS NOT NULL);

ALTER TABLE movimento_caixa DROP CONSTRAINT movimento_caixa_venda_so_em_venda;
ALTER TABLE movimento_caixa ADD CONSTRAINT movimento_caixa_venda_so_em_venda
    CHECK (venda_id IS NULL OR tipo IN ('VENDA', 'ESTORNO', 'RECEBIMENTO'));

ALTER TABLE movimento_caixa ADD COLUMN recebimento_id uuid REFERENCES recebimento (id);
ALTER TABLE movimento_caixa ADD CONSTRAINT movimento_caixa_recebimento_consistente
    CHECK ((tipo = 'RECEBIMENTO' AND recebimento_id IS NOT NULL AND venda_id IS NOT NULL)
        OR (tipo <> 'RECEBIMENTO' AND recebimento_id IS NULL));
CREATE UNIQUE INDEX uq_movimento_caixa_recebimento
    ON movimento_caixa (recebimento_id) WHERE recebimento_id IS NOT NULL;

-- A unicidade antiga por Venda e tipo continua apenas para conclusão e estorno. A mesma
-- Venda pode receber vários pagamentos parciais na mesma sessão.
DROP INDEX uq_movimento_caixa_venda_por_sessao;
CREATE UNIQUE INDEX uq_movimento_caixa_venda_por_sessao
    ON movimento_caixa (sessao_caixa_id, venda_id, tipo)
    WHERE venda_id IS NOT NULL AND tipo IN ('VENDA', 'ESTORNO');

COMMENT ON COLUMN movimento_caixa.recebimento_id IS
    'Identifica o recebimento em dinheiro para que sua reentrega pelo outbox não duplique a entrada.';
