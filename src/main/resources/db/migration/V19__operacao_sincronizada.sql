-- O resultado de cada operação que o dispositivo registrou sem rede e enviou depois, gravado na
-- mesma transação do efeito. É o que torna o reenvio inofensivo: a mesma Conta com o mesmo id de
-- operação devolve o resultado gravado em vez de aplicar de novo (RNF02, RNF03).
CREATE TABLE operacao_sincronizada (
    id            uuid          PRIMARY KEY,
    conta_id      uuid          NOT NULL REFERENCES conta (id),
    operacao_id   uuid          NOT NULL,
    usuario_id    uuid          NOT NULL REFERENCES usuario (id),
    tipo          varchar(60)   NOT NULL,
    registro_id   uuid          NOT NULL,
    payload       jsonb         NOT NULL,
    versao_base   bigint,
    depende_de    jsonb         NOT NULL,
    criada_em     timestamptz   NOT NULL,
    recebida_em   timestamptz   NOT NULL,
    resultado     varchar(20)   NOT NULL,
    versao        bigint,
    detalhe       text,
    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres. O erro transitório não é gravado:
    -- o reenvio precisa tentar de novo.
    CONSTRAINT operacao_sincronizada_resultado_valido
        CHECK (resultado IN ('APLICADA', 'APLICADA_COM_REVISAO', 'NAO_APLICADA')),
    -- Revisão e recusa sempre dizem por quê; a operação aplicada sem pendência não tem o que dizer.
    CONSTRAINT operacao_sincronizada_detalhe_da_pendencia
        CHECK (resultado = 'APLICADA' OR detalhe IS NOT NULL)
);

-- A chave é a Conta mais o id da operação: outra Conta pode usar o mesmo id sem ver nem afetar o
-- primeiro resultado (RNF05). O índice também serve ao filtro por conta.
CREATE UNIQUE INDEX uq_operacao_sincronizada_por_conta
    ON operacao_sincronizada (conta_id, operacao_id);

COMMENT ON TABLE operacao_sincronizada IS
    'Resultado de cada operação enviada pelo dispositivo, por Conta, gravado na mesma transação do efeito.';
COMMENT ON COLUMN operacao_sincronizada.operacao_id IS
    'Id gerado no dispositivo para o gesto; com a conta, é a chave de idempotência.';
COMMENT ON COLUMN operacao_sincronizada.usuario_id IS
    'Quem enviou, lido do token. O mesmo id enviado por outro usuário da Conta é conflito.';
COMMENT ON COLUMN operacao_sincronizada.registro_id IS
    'O Produto, Cliente, SessaoCaixa ou Venda que o gesto cria ou altera.';
COMMENT ON COLUMN operacao_sincronizada.payload IS
    'O conteúdo do gesto como o dispositivo gravou, comparado no reenvio e preservado para revisão.';
COMMENT ON COLUMN operacao_sincronizada.depende_de IS
    'Ids das operações que precisavam ter resultado antes desta.';
COMMENT ON COLUMN operacao_sincronizada.criada_em IS
    'Instante do gesto no relógio do dispositivo.';
COMMENT ON COLUMN operacao_sincronizada.recebida_em IS
    'Instante em que o servidor aplicou ou recusou o gesto; permite medir o atraso da sincronização.';
COMMENT ON COLUMN operacao_sincronizada.versao IS
    'Revisão do Produto, Cliente ou SessaoCaixa depois do gesto; nula na Venda, que não tem revisão.';
COMMENT ON COLUMN operacao_sincronizada.detalhe IS
    'Por que o gesto foi para revisão ou foi recusado.';
