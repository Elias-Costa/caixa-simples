-- Agregado Produto: o limiar do alerta de estoque baixo (RF20) e a convenção de sinal do ajuste
-- manual (RF19), que a V9 deixou em aberto de propósito para ser decidida com o caso de uso na mão.
--
-- O limiar é por produto, porque "baixo" depende do item: 2 kg de queijo e 2 garrafas de água não
-- são o mesmo "baixo", e um número único por conta não serviria a um catálogo misto. Nasce em zero
-- para toda linha, o que faz o alerta avisar quando o item acabou (saldo zero ou negativo) mesmo
-- sem ninguém ter configurado nada; quem informa um mínimo maior passa a ser avisado antes.
--
-- Sempre preenchida, inclusive em SERVICO, pelo mesmo motivo de estoque_atual: uma coluna anulável
-- espalharia tratamento de nulo por todo leitor, e o alerta já filtra por tipo e por conta com o
-- controle ligado.
ALTER TABLE produto
    ADD COLUMN estoque_minimo numeric(12,3) NOT NULL DEFAULT 0;

-- Mínimo negativo não é limiar: com o saldo podendo ficar negativo, um mínimo abaixo de zero
-- esconderia justamente o produto que já vendeu mais do que tinha.
ALTER TABLE produto
    ADD CONSTRAINT produto_estoque_minimo_nao_negativo
        CHECK (estoque_minimo >= 0);

COMMENT ON COLUMN produto.estoque_minimo IS
    'Limiar do alerta de estoque baixo (RF20): o produto está baixo quando estoque_atual <= estoque_minimo. Zero por padrão, o que avisa quando o item acabou. Sempre preenchido, inclusive em SERVICO, que nunca alerta.';

-- A V9 gravou estes comentários dizendo que a convenção de sinal do ajuste ainda não existia.
-- Agora existe: em AJUSTE a quantidade é a diferença com sinal, positiva quando a contagem achou
-- mais do que o saldo registrava, negativa em perda ou quebra. Reemitidos para o banco contar a
-- verdade inteira, como a V9 fez com produto.estoque_atual.
COMMENT ON COLUMN movimento_estoque.tipo IS
    'ENTRADA e SAIDA carregam o sinal: SAIDA subtrai do saldo, ENTRADA soma. AJUSTE é o acerto manual do RF19, e nele o sinal está na quantidade.';
COMMENT ON COLUMN movimento_estoque.quantidade IS
    'Três casas, como item_venda.quantidade e produto.estoque_atual: venda fracionada é real. Positiva em ENTRADA e SAIDA, com o sinal no tipo. Em AJUSTE é a diferença com sinal: -2 é uma perda de duas unidades, +3 é uma contagem que achou três a mais.';
