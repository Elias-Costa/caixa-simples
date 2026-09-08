-- Etapa 1.3 do plano de implementacao (passo R05 do roteiro): catalogo inicial sugerido.
--
-- Dicionario de dados: modelo-dados-caixa-simples.md §3, mais a coluna `tipo` da D20c.
-- Tipos de coluna: todos saem da tabela da D12 — nenhum tamanho inventado aqui.
--
-- Cobre RF32 (sugerir catalogo inicial com base no tipo de negocio informado no primeiro acesso).
--
-- ESTA E A TABELA SEM `conta_id`, e a ausencia e o ponto dela: e dado de referencia da PLATAFORMA,
-- nao de negocio de nenhuma conta. Junto de `conta` (cujo id *e* o tenant) e `credencial`
-- (consultada antes de existir tenant), fecha as tres — e so tres — excecoes ao filtro de tenant
-- descritas em .claude/rules/multi-tenancy.md. O isolamento continua de pe porque as linhas daqui
-- sao COPIADAS para `produto`, nunca referenciadas ao vivo: depois de copiado, o item pertence a
-- conta como qualquer outro produto e some da vista das demais.

CREATE TABLE modelo_produto (
    id                   uuid          PRIMARY KEY,
    tipo_negocio         varchar(60)   NOT NULL,
    nome                 varchar(120)  NOT NULL,
    categoria            varchar(60),
    unidade              varchar(60),
    tipo                 varchar(20)   NOT NULL,
    atributos_sugeridos  jsonb         NOT NULL DEFAULT '{}'::jsonb,

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres (P5). Mesmo dominio de
    -- `produto.tipo`, porque este valor e copiado direto para la.
    CONSTRAINT modelo_produto_tipo_valido
        CHECK (tipo IN ('PRODUTO', 'SERVICO'))
);

-- D20e — o casamento com `conta.tipo_negocio` ignora maiuscula/minuscula, mesmo tratamento que a
-- P3 da ao e-mail e a D11 ao codigo do produto: o campo aceita texto livre em "outro" (modelo de
-- dados §3), e "Cafeteria" digitado no cadastro da conta nao pode deixar de casar com "cafeteria".
-- O indice sustenta exatamente a consulta que o caso de uso faz.
CREATE INDEX idx_modelo_produto_tipo_negocio ON modelo_produto (lower(tipo_negocio));

-- D20b — catalogo de fabrica: so `cafeteria`, o unico negocio-piloto que existe de verdade
-- (escopo §11). Loja, salao e oficina aparecem no escopo §1 como tipos possiveis, mas nenhum
-- documento lista os itens de nenhum deles — inventar tres catalogos que ninguem validou seria
-- furar a regra de ouro do CLAUDE.md. Entram quando o segundo piloto existir.
--
-- Os ids sao literais fixos de proposito: dado de referencia precisa ter o mesmo id em todo
-- ambiente, para uma correcao futura poder mirar a linha certa.
--
-- `atributos_sugeridos` fica vazio em todas: nenhum documento define quais atributos uma cafeteria
-- usa, e chave de JSONB inventada aqui viraria contrato para o RF02 sem ninguem ter decidido.
--
-- Nomes sem acento acompanham o resto do repositorio, que e integralmente ASCII.
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
    'Catalogo sugerido por tipo de negocio (RF32). Dado de referencia da plataforma: unica tabela do sistema sem conta_id por nao ser dado de conta nenhuma (modelo-dados §5).';
COMMENT ON COLUMN modelo_produto.tipo_negocio IS
    'Casa com conta.tipo_negocio, ignorando maiuscula/minuscula (D20e).';
COMMENT ON COLUMN modelo_produto.tipo IS
    'D20c — nao estava no modelo de dados §3 e precisou entrar: produto.tipo e NOT NULL, e sem esta coluna um corte de cabelo seria copiado como PRODUTO, carregando estoque que nao existe (RF17/P4).';
COMMENT ON COLUMN modelo_produto.atributos_sugeridos IS
    'RF02 — copiado para produto.atributos junto com o resto da linha.';
