-- Dois planos, o gratuito e o completo, que passa a ter tudo o que estava nos dois planos pagos. Sai
-- o plano intermediário e, com ele, o upgrade com a diferença proporcional: o único caminho pago é a
-- adesão do gratuito ao completo, e depois a renovação.
--
-- Só um banco de desenvolvimento ou de ensaio tem linhas do plano que sai. A Conta nele passa ao
-- completo com o mesmo vencimento: não perde recurso nenhum e renova pelo preço dele. O pedido do
-- plano ou do caminho que sai é apagado, aberto ou fechado: nenhum correspondeu a um Pix de verdade,
-- e a aplicação não saberia ler uma linha com um valor que não existe mais.
UPDATE conta
   SET plano = 'COMPLETO'
 WHERE plano = 'CAIXA_SIMPLES';

DELETE FROM pedido_de_plano
 WHERE plano = 'CAIXA_SIMPLES'
    OR tipo = 'UPGRADE';

ALTER TABLE conta
    DROP CONSTRAINT conta_plano_valido,
    ADD CONSTRAINT conta_plano_valido CHECK (plano IN ('GRATIS', 'COMPLETO'));

ALTER TABLE pedido_de_plano
    DROP CONSTRAINT pedido_de_plano_tipo_valido,
    DROP CONSTRAINT pedido_de_plano_plano_pago,
    DROP CONSTRAINT pedido_de_plano_upgrade_para_completo,
    ADD CONSTRAINT pedido_de_plano_tipo_valido CHECK (tipo IN ('ADESAO', 'RENOVACAO')),
    -- O plano gratuito não tem pedido.
    ADD CONSTRAINT pedido_de_plano_plano_pago CHECK (plano = 'COMPLETO');

COMMENT ON TABLE pedido_de_plano IS
    'Pedido de adesão ou renovação de plano, ativado por um código conferido pela aplicação.';
COMMENT ON COLUMN pedido_de_plano.valor IS
    'O que a Conta paga por Pix: a mensalidade do dia do pedido.';
COMMENT ON COLUMN pedido_de_plano.periodo_inicio IS
    'Primeiro dia do período pago. Na adesão, nulo até a aplicação do código, quando o ciclo começa.';
