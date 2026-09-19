-- Agregado Produto: o membro que faltava, o movimento de estoque.
--
-- Cobre RF18 (baixa automática a cada venda concluída, quando o controle de estoque da conta está
-- ligado, RF17). Prepara RF19 (ajuste manual com motivo) e o estorno do cancelamento (RF12): os
-- dois só ganham lógica adiante, mas migration publicada é imutável, então as regras de schema que
-- eles precisam entram agora, para não prender o desenho deles depois.
--
-- A raiz, produto, existe desde a V2, com estoque_atual em zero à espera deste membro. A invariante
-- que os dois guardam juntos:
--   estoque_atual = soma assinada dos movimentos, atualizado na mesma transação de cada um.
-- O saldo é coluna viva, não cálculo: quem lê o produto nunca soma o histórico. É por isso que a
-- entidade JPA do membro é package-private, não tem repositório próprio, e o único caminho que
-- escreve estoque_atual exige o movimento junto.
CREATE TABLE movimento_estoque (
    id           uuid          PRIMARY KEY,
    conta_id     uuid          NOT NULL REFERENCES conta (id),
    produto_id   uuid          NOT NULL REFERENCES produto (id),
    venda_id     uuid          REFERENCES venda (id),
    tipo         varchar(20)   NOT NULL,
    quantidade   numeric(12,3) NOT NULL,
    motivo       varchar(120),
    criado_em    timestamptz   NOT NULL DEFAULT now(),

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres.
    CONSTRAINT movimento_estoque_tipo_valido
        CHECK (tipo IN ('ENTRADA', 'SAIDA', 'AJUSTE')),

    -- Movimento de zero não é movimento: nada entrou nem saiu.
    CONSTRAINT movimento_estoque_quantidade_nao_nula
        CHECK (quantidade <> 0),

    -- Em ENTRADA e SAIDA, quem carrega o sinal é o tipo, como em movimento_caixa: uma saída de
    -- duas unidades grava 2, nunca -2. O ajuste manual ainda não existe, e a convenção de sinal
    -- dele é decisão de quando existir; este CHECK não a prende.
    CONSTRAINT movimento_estoque_quantidade_positiva_fora_de_ajuste
        CHECK (tipo = 'AJUSTE' OR quantidade > 0),

    -- RF19: ajuste manual sempre tem motivo (perda, quebra, contagem). A regra também vai viver no
    -- domínio; aqui ela é a rede embaixo, para nenhum script deixar um ajuste sem justificativa.
    CONSTRAINT movimento_estoque_motivo_obrigatorio_em_ajuste
        CHECK (tipo <> 'AJUSTE' OR motivo IS NOT NULL)
);

-- Mesmo sendo membro de agregado, a tabela leva conta_id: toda tabela de negócio leva, e as três
-- exceções do sistema já estão todas criadas. A coluna direta é o que deixa o @TenantId filtrar
-- sem depender de JOIN com a raiz.
CREATE INDEX idx_movimento_estoque_conta ON movimento_estoque (conta_id);

-- A escrita é sempre pela raiz; este índice é o que sustenta esse caminho.
CREATE INDEX idx_movimento_estoque_produto ON movimento_estoque (produto_id);

-- O evento de venda concluída é entregue ao menos uma vez, e não exatamente uma. O código pergunta
-- antes de baixar e reconhece a reentrega; este índice é a rede para o caso que o código não
-- alcança, duas entregas do mesmo evento ao mesmo tempo. Inclui o tipo para que o estorno de um
-- cancelamento, uma ENTRADA da mesma venda, caiba ao lado da SAIDA.
CREATE UNIQUE INDEX uq_movimento_estoque_venda_por_produto
    ON movimento_estoque (produto_id, venda_id, tipo)
    WHERE venda_id IS NOT NULL;

COMMENT ON TABLE  movimento_estoque IS
    'Entrada, saída ou ajuste de estoque de um produto. Membro do agregado Produto: sem repositório próprio, gravado sempre junto do saldo da raiz.';
COMMENT ON COLUMN movimento_estoque.produto_id IS
    'A raiz do agregado. Serviço nunca aparece aqui: só produto tem estoque.';
COMMENT ON COLUMN movimento_estoque.venda_id IS
    'Preenchido quando o movimento vem de uma venda: a baixa da conclusão e, adiante, o estorno do cancelamento. Referência entre agregados é por id; a FOREIGN KEY protege o dado sem criar objeto navegável.';
COMMENT ON COLUMN movimento_estoque.tipo IS
    'ENTRADA e SAIDA carregam o sinal: SAIDA subtrai do saldo, ENTRADA soma. AJUSTE é o acerto manual do RF19.';
COMMENT ON COLUMN movimento_estoque.quantidade IS
    'Três casas, como item_venda.quantidade e produto.estoque_atual: venda fracionada é real. Positiva em ENTRADA e SAIDA; o sinal vem do tipo.';
COMMENT ON COLUMN movimento_estoque.motivo IS
    'RF19: obrigatório em AJUSTE. varchar(120) é o limite de descrição curta do schema, o mesmo do motivo de sangria e suprimento.';
COMMENT ON COLUMN movimento_estoque.criado_em IS
    'Dá ordem de leitura ao histórico do produto. Empate de instante fica sem ordem definida, e isso é aceitável.';

-- O comentário gravado pela V2 não dizia que o saldo pode ficar negativo, porque a regra só
-- nasceu com a baixa; reemitido para o banco contar a verdade inteira.
COMMENT ON COLUMN produto.estoque_atual IS
    'Saldo consolidado, sempre preenchido, inclusive em SERVICO, que nunca recebe movimento. Escrito só junto de um movimento_estoque, na mesma transação, para o alerta do RF20 não ter de somar o histórico. Pode ficar negativo: a venda que baixou mais do que havia já aconteceu, e o acerto é um ajuste de contagem.';
