-- Etapa 1.1 do plano de implementacao (passo R02 do roteiro): raiz do agregado Produto.
--
-- Dicionario de dados: modelo-dados-caixa-simples.md §3.
-- Tipos de coluna: todos saem da tabela da D12 — nenhum tamanho inventado aqui.
--
-- Cobre RF01 (cadastro de produto/servico), RF02/RNF12 (atributos variaveis por tipo de negocio
-- sem alterar schema) e RF05 (inativacao sem exclusao).
--
-- Os membros do agregado ficam de fora de proposito: `movimento_estoque` nasce na etapa 1.7
-- (passo R15), junto com a invariante estoque_atual = soma dos movimentos.

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

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres (P5). Servico nao gera movimento de
    -- estoque; a diferenca entre os dois vive aqui, nao em duas tabelas.
    CONSTRAINT produto_tipo_valido
        CHECK (tipo IN ('PRODUTO', 'SERVICO')),

    -- D16c: preco zero e valido (cortesia, brinde, item de acompanhamento); negativo nao e preco.
    CONSTRAINT produto_preco_nao_negativo
        CHECK (preco >= 0)
);

-- Todo acesso a produto passa pelo filtro de @TenantId; o indice sustenta esse filtro.
CREATE INDEX idx_produto_conta ON produto (conta_id);

-- D11 — codigo do produto (RF06). Tres coisas de uma vez, e cada uma tem motivo:
--   * conta_id na chave, porque a unicidade e POR CONTA, nunca global;
--   * lower(codigo), porque `ABC-12` e `abc-12` sao o mesmo codigo — o operador digita rapido no
--     balcao e nao pode perder a venda por causa de maiuscula (mesma abordagem do e-mail, P3);
--   * WHERE ativo, porque o soft delete mantem o produto no banco para sempre e unicidade global
--     "queimaria" um codigo a cada item que sai de linha.
-- Custo aceito: o mesmo codigo pode ter apontado para produtos diferentes ao longo do tempo, entao
-- relatorio historico por codigo e ambiguo e nao deve ser oferecido — o historico se apoia no id.
-- Coluna anulavel funciona sem caso especial: no Postgres um indice unico aceita varios NULL.
CREATE UNIQUE INDEX idx_produto_codigo ON produto (conta_id, lower(codigo)) WHERE ativo;

-- RNF12 — o que varia por nicho vive no JSONB, nao em coluna nova nem em EAV (arquitetura §4).
-- jsonb_path_ops em vez do jsonb_ops padrao: indice menor e mais rapido para o operador de
-- continencia `@>`, que e a forma de filtro que este projeto usa. Em troca ele nao atende busca
-- por existencia de chave (`?`), que nenhum requisito pede.
CREATE INDEX idx_produto_atributos ON produto USING gin (atributos jsonb_path_ops);

COMMENT ON TABLE  produto IS
    'Produto fisico ou servico. Raiz do agregado Produto (modelo-dados §4).';
COMMENT ON COLUMN produto.codigo IS
    'D11 — texto livre e opcional: numero interno, EAN da embalagem ou referencia do fabricante, conforme o negocio. Unico por conta apenas entre ativos.';
COMMENT ON COLUMN produto.categoria IS
    'D16a — opcional: exigir categoria trava o cadastro rapido no balcao.';
COMMENT ON COLUMN produto.unidade IS
    'D16a — opcional. Ex.: "un", "kg", "hora".';
COMMENT ON COLUMN produto.estoque_atual IS
    'D16b — saldo consolidado, sempre preenchido, inclusive em SERVICO (que simplesmente nunca recebe movimento). Atualizado na mesma transacao de cada MovimentoEstoque (R15), para o alerta do RF20 nao ter de somar o historico.';
COMMENT ON COLUMN produto.atributos IS
    'RF02/RNF12 — atributos especificos do tipo de negocio. Produto sem atributo tem objeto vazio, nao ausencia: evita tratar null em todo leitor.';
