-- Raiz do agregado Produto.
--
-- Cobre RF01 (cadastro de produto ou serviço), RF02 e RNF12 (atributos variáveis por tipo de
-- negócio sem alterar schema) e RF05 (inativação sem exclusão).
--
-- Os membros do agregado ficam de fora de propósito: `movimento_estoque` nasce junto com o módulo
-- de estoque, e com ele a invariante estoque_atual = soma dos movimentos.

CREATE TABLE produto (
    id             uuid          PRIMARY KEY,
    conta_id       uuid          NOT NULL REFERENCES conta (id),
    nome           varchar(120)  NOT NULL,
    codigo         varchar(60),
    preco          numeric(12,2) NOT NULL,
    categoria      varchar(60),
    unidade        varchar(60),
    tipo           varchar(20)   NOT NULL,
    estoque_atual  numeric(12,3) NOT NULL DEFAULT 0,
    atributos      jsonb         NOT NULL DEFAULT '{}'::jsonb,
    ativo          boolean       NOT NULL DEFAULT true,
    criado_em      timestamptz   NOT NULL DEFAULT now(),

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres. Serviço não gera movimento de
    -- estoque; a diferença entre os dois vive aqui, não em duas tabelas.
    CONSTRAINT produto_tipo_valido
        CHECK (tipo IN ('PRODUTO', 'SERVICO')),

    -- Preço zero é válido (cortesia, brinde, item de acompanhamento); negativo não é preço.
    CONSTRAINT produto_preco_nao_negativo
        CHECK (preco >= 0)
);

-- Todo acesso a produto passa pelo filtro de @TenantId; o índice sustenta esse filtro.
CREATE INDEX idx_produto_conta ON produto (conta_id);

-- Código do produto (RF06). Três coisas de uma vez, e cada uma tem motivo:
--   * conta_id na chave, porque a unicidade é POR CONTA, nunca global;
--   * lower(codigo), porque `ABC-12` e `abc-12` são o mesmo código: o operador digita rápido no
--     balcão e não pode perder a venda por causa de maiúscula, mesmo tratamento dado ao e-mail;
--   * WHERE ativo, porque o soft delete mantém o produto no banco para sempre, e unicidade global
--     tiraria de circulação um código a cada item que sai de linha.
-- Custo aceito: o mesmo código pode ter apontado para produtos diferentes ao longo do tempo, então
-- relatório histórico por código é ambíguo e não deve ser oferecido; o histórico se apoia no id.
-- Coluna anulável funciona sem caso especial: no Postgres um índice único aceita vários NULL.
CREATE UNIQUE INDEX idx_produto_codigo ON produto (conta_id, lower(codigo)) WHERE ativo;

-- RNF12: o que varia por nicho vive no JSONB, não em coluna nova nem em EAV.
-- jsonb_path_ops em vez do jsonb_ops padrão: índice menor e mais rápido para o operador de
-- continência `@>`, que é a forma de filtro que este projeto usa. Em troca ele não atende busca
-- por existência de chave (`?`), que nenhum requisito pede.
CREATE INDEX idx_produto_atributos ON produto USING gin (atributos jsonb_path_ops);

COMMENT ON TABLE  produto IS
    'Produto físico ou serviço. Raiz do agregado Produto.';
COMMENT ON COLUMN produto.codigo IS
    'Texto livre e opcional: número interno, EAN da embalagem ou referência do fabricante, conforme o negócio. Único por conta apenas entre ativos.';
COMMENT ON COLUMN produto.categoria IS
    'Opcional: exigir categoria trava o cadastro rápido no balcão.';
COMMENT ON COLUMN produto.unidade IS
    'Opcional. Exemplos: un, kg, hora.';
COMMENT ON COLUMN produto.estoque_atual IS
    'Saldo consolidado, sempre preenchido, inclusive em SERVICO, que simplesmente nunca recebe movimento. Atualizado na mesma transação de cada MovimentoEstoque, para o alerta do RF20 não ter de somar o histórico.';
COMMENT ON COLUMN produto.atributos IS
    'RF02 e RNF12: atributos específicos do tipo de negócio. Produto sem atributo tem objeto vazio, não ausência, o que evita tratar null em todo leitor.';
