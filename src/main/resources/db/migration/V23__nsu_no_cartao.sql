-- NSU do pagamento em cartão: o número impresso no comprovante da maquininha.
--
-- O cartão é lançado à mão (RF26) e não passa pela gaveta, então uma venda paga em dinheiro e
-- lançada como cartão deixa o dinheiro sair sem que o fechamento do caixa (RF15) acuse diferença.
-- Pelo faturamento filtrado por cartão (RF24), o dono só confere o total contra o extrato da
-- operadora; com o NSU de cada parcela, confere pagamento por pagamento.

-- A exigência é da Conta, como o controle de estoque, e nasce desligada nas Contas novas e nas
-- existentes: ninguém recebe um campo obrigatório sem pedir. Ligar não alcança o passado; o que foi
-- gravado sem NSU continua válido.
ALTER TABLE conta
    ADD COLUMN nsu_obrigatorio boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN conta.nsu_obrigatorio IS
    'RF26: o pagamento em cartão com rede exige o NSU. Desligada, o NSU é opcional. Não alcança o que já foi gravado.';

-- Texto livre de até 40 caracteres, porque cada operadora imprime um identificador diferente, alguns
-- com letras. Nulo quando não foi informado, nunca texto vazio. O repetido na mesma Conta não é
-- recusado: o número só é único dentro de cada operadora ou maquininha, e dois pagamentos legítimos
-- podem repeti-lo. O recebimento de fiado em cartão também passa na maquininha e aparece no extrato,
-- por isso leva o mesmo campo.
ALTER TABLE pagamento
    ADD COLUMN nsu varchar(40);

ALTER TABLE recebimento
    ADD COLUMN nsu varchar(40);

-- As restrições repetem o que o domínio já recusa, para um script de correção não gravar o que o
-- código recusa: NSU só no cartão, nunca vazio e sem espaço nas pontas, que o domínio corta.
ALTER TABLE pagamento
    ADD CONSTRAINT pagamento_nsu_so_em_cartao
        CHECK (nsu IS NULL OR forma = 'CARTAO');

ALTER TABLE pagamento
    ADD CONSTRAINT pagamento_nsu_sem_espaco_nas_pontas
        CHECK (nsu IS NULL OR (nsu <> '' AND nsu = btrim(nsu)));

ALTER TABLE recebimento
    ADD CONSTRAINT recebimento_nsu_so_em_cartao
        CHECK (nsu IS NULL OR forma = 'CARTAO');

ALTER TABLE recebimento
    ADD CONSTRAINT recebimento_nsu_sem_espaco_nas_pontas
        CHECK (nsu IS NULL OR (nsu <> '' AND nsu = btrim(nsu)));

COMMENT ON COLUMN pagamento.nsu IS
    'RF26: NSU do comprovante da maquininha, só em CARTAO e nulo quando não foi informado. Entra no lançamento da parcela e não é corrigido depois.';

COMMENT ON COLUMN recebimento.nsu IS
    'RF26: NSU do comprovante da maquininha, só em CARTAO e nulo quando não foi informado. Entra no recebimento e não é corrigido depois.';
