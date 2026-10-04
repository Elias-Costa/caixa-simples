-- O ciclo mensal do plano pago e o pedido de plano que o ADMIN faz no aplicativo (RF31).
--
-- O plano pago vence todo mês no mesmo dia, o da aplicação do código de adesão; num mês mais curto,
-- vence no último dia, sem mudar o dia dos meses seguintes. O plano gratuito não tem vencimento.
ALTER TABLE conta
    ADD COLUMN dia_de_vencimento  integer,
    ADD COLUMN proximo_vencimento date;

-- Uma Conta paga sem ciclo só existe num banco local, criada por SQL à mão: passa a vencer no dia
-- desta migration, no fuso do balcão. Num banco que nasce com esta migration, nada é alterado.
UPDATE conta
   SET dia_de_vencimento  = extract(day FROM (now() AT TIME ZONE 'America/Bahia'))::integer,
       proximo_vencimento = (now() AT TIME ZONE 'America/Bahia')::date
 WHERE plano <> 'GRATIS';

ALTER TABLE conta
    ADD CONSTRAINT conta_ciclo_so_no_plano_pago CHECK (
        (plano = 'GRATIS' AND dia_de_vencimento IS NULL AND proximo_vencimento IS NULL)
        OR (plano <> 'GRATIS' AND dia_de_vencimento IS NOT NULL AND proximo_vencimento IS NOT NULL)),
    ADD CONSTRAINT conta_dia_de_vencimento_valido CHECK (dia_de_vencimento BETWEEN 1 AND 31);

COMMENT ON COLUMN conta.dia_de_vencimento IS
    'Dia do mês em que o plano pago vence: o da aplicação do código de adesão. Nulo no plano gratuito.';
COMMENT ON COLUMN conta.proximo_vencimento IS
    'Fim, exclusive, do último período pago. A tolerância e a suspensão contam a partir dele. Nulo no plano gratuito.';

-- Todo pagamento começa por um pedido: adesão, upgrade ou renovação. O código que o ativa é a
-- assinatura do id do pedido e do plano com um segredo do servidor, entregue pelo mantenedor depois
-- de conferir o Pix. O código não é gravado: a aplicação o recalcula para conferir.
CREATE TABLE pedido_de_plano (
    id              uuid          PRIMARY KEY,
    conta_id        uuid          NOT NULL REFERENCES conta (id),
    tipo            varchar(20)   NOT NULL,
    plano           varchar(20)   NOT NULL,
    valor           numeric(12,2) NOT NULL,
    periodo_inicio  date,
    periodo_fim     date,
    situacao        varchar(20)   NOT NULL,
    criado_em       timestamptz   NOT NULL,
    criado_por      uuid          NOT NULL REFERENCES usuario (id),
    aplicado_em     timestamptz,
    aplicado_por    uuid          REFERENCES usuario (id),
    CONSTRAINT pedido_de_plano_tipo_valido
        CHECK (tipo IN ('ADESAO', 'UPGRADE', 'RENOVACAO')),
    -- O plano gratuito não tem pedido.
    CONSTRAINT pedido_de_plano_plano_pago
        CHECK (plano IN ('CAIXA_SIMPLES', 'COMPLETO')),
    CONSTRAINT pedido_de_plano_situacao_valida
        CHECK (situacao IN ('ABERTO', 'APLICADO', 'SUBSTITUIDO')),
    -- Zero é possível: a diferença proporcional de um upgrade pode arredondar a nada.
    CONSTRAINT pedido_de_plano_valor_nao_negativo
        CHECK (valor >= 0),
    CONSTRAINT pedido_de_plano_upgrade_para_completo
        CHECK (tipo <> 'UPGRADE' OR plano = 'COMPLETO'),
    -- A adesão só conhece o período quando o código é aplicado, porque o ciclo começa nesse dia.
    -- Upgrade e renovação nascem com o período que pagam.
    CONSTRAINT pedido_de_plano_periodo_coerente CHECK (
        CASE
            WHEN tipo = 'ADESAO' AND situacao <> 'APLICADO'
                THEN periodo_inicio IS NULL AND periodo_fim IS NULL
            ELSE periodo_inicio IS NOT NULL AND periodo_fim IS NOT NULL
                AND periodo_inicio < periodo_fim
        END),
    CONSTRAINT pedido_de_plano_aplicacao_registrada CHECK (
        (situacao = 'APLICADO' AND aplicado_em IS NOT NULL AND aplicado_por IS NOT NULL)
        OR (situacao <> 'APLICADO' AND aplicado_em IS NULL AND aplicado_por IS NULL))
);

-- Todo acesso ao pedido passa pelo filtro de @TenantId; o índice sustenta esse filtro.
CREATE INDEX idx_pedido_de_plano_conta ON pedido_de_plano (conta_id);

-- Um pedido aberto por Conta: pedir de novo substitui o aberto. O índice segura a regra mesmo que
-- dois pedidos cheguem juntos.
CREATE UNIQUE INDEX uq_pedido_de_plano_aberto_por_conta
    ON pedido_de_plano (conta_id)
    WHERE situacao = 'ABERTO';

COMMENT ON TABLE pedido_de_plano IS
    'Pedido de adesão, upgrade ou renovação de plano, ativado por um código conferido pela aplicação.';
COMMENT ON COLUMN pedido_de_plano.valor IS
    'O que a Conta paga por Pix: a mensalidade, ou a diferença proporcional no upgrade.';
COMMENT ON COLUMN pedido_de_plano.periodo_inicio IS
    'Primeiro dia do período pago. No upgrade, o dia do pedido, em que o crédito é presumido.';
COMMENT ON COLUMN pedido_de_plano.periodo_fim IS
    'Fim, exclusive, do período pago: o vencimento seguinte.';
COMMENT ON COLUMN pedido_de_plano.situacao IS
    'SUBSTITUIDO quando a Conta pediu de novo antes de aplicar o código; o código dele é recusado.';
