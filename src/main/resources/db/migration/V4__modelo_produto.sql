-- Catálogo inicial sugerido por tipo de negócio.
--
-- Cobre RF32 (sugerir catálogo inicial com base no tipo de negócio informado no primeiro acesso).
--
-- ESTA É A TABELA SEM `conta_id`, e a ausência é o ponto dela: é dado de referência da PLATAFORMA,
-- não de negócio de nenhuma conta. Junto de `conta`, cujo id é o próprio tenant, e `credencial`,
-- consultada antes de existir tenant, fecha as três, e só três, exceções ao filtro de tenant. O
-- isolamento continua de pé porque as linhas daqui são COPIADAS para `produto`, nunca
-- referenciadas ao vivo: depois de copiado, o item pertence à conta como qualquer outro produto e
-- some da vista das demais.

CREATE TABLE modelo_produto (
    id                   uuid          PRIMARY KEY,
    tipo_negocio         varchar(60)   NOT NULL,
    nome                 varchar(120)  NOT NULL,
    categoria            varchar(60),
    unidade              varchar(60),
    tipo                 varchar(20)   NOT NULL,
    atributos_sugeridos  jsonb         NOT NULL DEFAULT '{}'::jsonb,

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres. Mesmo domínio de `produto.tipo`,
    -- porque este valor é copiado direto para lá.
    CONSTRAINT modelo_produto_tipo_valido
        CHECK (tipo IN ('PRODUTO', 'SERVICO'))
);

-- O casamento com `conta.tipo_negocio` ignora maiúscula e minúscula, mesmo tratamento dado ao
-- e-mail e ao código do produto: o campo aceita texto livre, e Cafeteria digitado no cadastro da
-- conta não pode deixar de casar com cafeteria. O índice sustenta exatamente a consulta que o caso
-- de uso faz.
CREATE INDEX idx_modelo_produto_tipo_negocio ON modelo_produto (lower(tipo_negocio));

-- Catálogo de fábrica com um tipo de negócio só, `cafeteria`. Loja, salão e oficina são tipos
-- previstos, mas os itens de cada um ainda não foram levantados com quem opera esse negócio, e
-- inventar três catálogos que ninguém validou entregaria sugestão errada logo no primeiro acesso.
-- Entram quando o levantamento existir.
--
-- Os ids são literais fixos de propósito: dado de referência precisa ter o mesmo id em todo
-- ambiente, para uma correção futura poder mirar a linha certa.
--
-- `atributos_sugeridos` fica vazio em todas: quais atributos uma cafeteria usa ainda não está
-- definido, e chave de JSONB inventada aqui viraria contrato para o RF02 sem decisão nenhuma.
INSERT INTO modelo_produto (id, tipo_negocio, nome, categoria, unidade, tipo) VALUES
    ('a5e1c000-0000-4000-8000-000000000001', 'cafeteria', 'Cafe expresso',     'Bebidas',  'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000002', 'cafeteria', 'Cafe coado',        'Bebidas',  'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000003', 'cafeteria', 'Cappuccino',        'Bebidas',  'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000004', 'cafeteria', 'Suco natural',      'Bebidas',  'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000005', 'cafeteria', 'Agua mineral',      'Bebidas',  'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000006', 'cafeteria', 'Pao de queijo',     'Salgados', 'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000007', 'cafeteria', 'Sanduiche natural', 'Salgados', 'un', 'PRODUTO'),
    ('a5e1c000-0000-4000-8000-000000000008', 'cafeteria', 'Bolo em fatia',     'Doces',    'un', 'PRODUTO');

COMMENT ON TABLE  modelo_produto IS
    'Catálogo sugerido por tipo de negócio (RF32). Dado de referência da plataforma: única tabela do sistema sem conta_id, por não ser dado de conta nenhuma.';
COMMENT ON COLUMN modelo_produto.tipo_negocio IS
    'Casa com conta.tipo_negocio, ignorando maiúscula e minúscula.';
COMMENT ON COLUMN modelo_produto.tipo IS
    'produto.tipo é NOT NULL, e sem esta coluna um corte de cabelo seria copiado como PRODUTO, carregando estoque que não existe (RF17).';
COMMENT ON COLUMN modelo_produto.atributos_sugeridos IS
    'RF02: copiado para produto.atributos junto com o resto da linha.';
