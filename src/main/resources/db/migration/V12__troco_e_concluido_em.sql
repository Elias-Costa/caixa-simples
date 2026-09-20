-- Agregado Venda: o que o comprovante não-fiscal (RF11) precisa e a V7 não guardava.
--
-- O comprovante traz itens, descontos, formas de pagamento e troco, e precisa sair igual numa
-- reimpressão. Duas coisas que a venda não gravava passam a ser gravadas: o troco de cada parcela
-- e o instante em que a venda foi concluída. Nenhuma das duas é regra nova; as duas já aconteciam
-- e se perdiam.

-- O troco é calculado pelo módulo de pagamentos no ato do lançamento (RF10) e, até aqui, voltava
-- só para a tela mostrar. Fica gravado na parcela, que é quem o produziu. Zero quando não há,
-- nunca nulo, pelo mesmo motivo de venda.valor_desconto: nenhum leitor precisa tratar ausência.
-- É o troco, e não o valor recebido, porque é o troco que o comprovante imprime; o recebido é a
-- soma dos dois, para quem precisar.
ALTER TABLE pagamento
    ADD COLUMN troco numeric(12,2) NOT NULL DEFAULT 0;

ALTER TABLE pagamento
    ADD CONSTRAINT pagamento_troco_nao_negativo
        CHECK (troco >= 0);

-- Só dinheiro devolve troco. Pix e cartão são pagos no valor exato, e o domínio já recusa valor
-- recebido nas duas formas; a restrição repete a regra para um script de correção não gravar o que
-- o código recusa.
ALTER TABLE pagamento
    ADD CONSTRAINT pagamento_troco_so_em_dinheiro
        CHECK (forma = 'DINHEIRO' OR troco = 0);

COMMENT ON COLUMN pagamento.troco IS
    'RF10: o que voltou para o cliente nesta parcela. Zero fora de dinheiro e quando o cliente pagou o valor exato. Gravado para o comprovante (RF11) sair igual numa reimpressão.';

-- A venda guardava só criado_em, que é quando a comanda abriu. O comprovante mostra quando o
-- cliente pagou, e numa comanda os dois instantes podem estar horas distantes. Nulo enquanto a
-- venda está ABERTA e em toda CANCELADA que nunca foi concluída; fica gravado depois do
-- cancelamento de uma venda concluída, porque a conclusão aconteceu e o histórico continua
-- contando isso.
ALTER TABLE venda
    ADD COLUMN concluido_em timestamptz;

-- As vendas concluídas antes desta migration não têm o instante real. O de abertura é a melhor
-- aproximação que existe, e é ele que a restrição abaixo exige.
UPDATE venda
    SET concluido_em = criado_em
    WHERE status = 'CONCLUIDA';

ALTER TABLE venda
    ADD CONSTRAINT venda_concluida_tem_instante
        CHECK (status <> 'CONCLUIDA' OR concluido_em IS NOT NULL);

COMMENT ON COLUMN venda.concluido_em IS
    'Quando a venda foi concluída, isto é, quando os pagamentos fecharam a conta. Nulo em ABERTA e em CANCELADA que nunca concluiu. É a data do comprovante (RF11).';
