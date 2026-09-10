-- Agregado Caixa: a sessão operacional e seus movimentos.
--
-- Prepara RF13 (abertura), RF14 (sangria e suprimento) e RF15 e RF16 (fechamento com conferência).
-- A lógica dos três fica de fora de propósito: aqui só nascem schema e mapeamento.
--
-- Vocabulário do domínio: SANGRIA é retirada de dinheiro, SUPRIMENTO é reforço de troco, e caixa é
-- a sessão operacional entre abertura e fechamento, não o dinheiro em espécie nem o sistema.

-- Raiz do agregado Caixa. A invariante que ela guarda:
--   valor_fechamento_esperado = valor_abertura + soma assinada dos movimentos.
CREATE TABLE sessao_caixa (
    id                         uuid          PRIMARY KEY,
    conta_id                   uuid          NOT NULL REFERENCES conta (id),
    usuario_id                 uuid          NOT NULL REFERENCES usuario (id),
    valor_abertura             numeric(12,2) NOT NULL,
    valor_fechamento_esperado  numeric(12,2) NOT NULL,
    valor_fechamento_contado   numeric(12,2),
    diferenca                  numeric(12,2),
    aberta_em                  timestamptz   NOT NULL DEFAULT now(),
    fechada_em                 timestamptz,
    status                     varchar(20)   NOT NULL,

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres.
    CONSTRAINT sessao_caixa_status_valido
        CHECK (status IN ('ABERTA', 'FECHADA'))
);

-- Todo acesso a sessao_caixa passa pelo filtro de @TenantId; o índice sustenta esse filtro.
CREATE INDEX idx_sessao_caixa_conta ON sessao_caixa (conta_id);

-- A unicidade de sessão aberta por operador não nasce aqui: ela chega na V6, junto com a regra de
-- abertura. Migration commitada não se edita, então a regra entrou em versão nova.

COMMENT ON TABLE  sessao_caixa IS
    'Sessão operacional do caixa entre abertura e fechamento. Raiz do agregado Caixa.';
COMMENT ON COLUMN sessao_caixa.usuario_id IS
    'Quem abriu a sessão. Referência entre agregados é por id, nunca objeto navegável.';
COMMENT ON COLUMN sessao_caixa.valor_fechamento_esperado IS
    'Coluna viva, não cálculo do fechamento: nasce igual a valor_abertura e é reescrita a cada movimento, na mesma transação. Mesmo padrão de produto.estoque_atual.';
COMMENT ON COLUMN sessao_caixa.valor_fechamento_contado IS
    'RF15: informado manualmente no fechamento; nulo enquanto a sessão está ABERTA.';
COMMENT ON COLUMN sessao_caixa.diferenca IS
    'RF15: esperado menos contado, gravado no fechamento; nulo enquanto a sessão está ABERTA.';

-- Membro do agregado: nunca lido nem escrito direto, sempre pela raiz. Por isso não existe
-- MovimentoCaixaRepository, e a entidade JPA correspondente é package-private.
--
-- Unifica venda, sangria e suprimento numa tabela só: o fechamento vira uma soma sobre esta
-- tabela, sem juntar três diferentes.
CREATE TABLE movimento_caixa (
    id               uuid          PRIMARY KEY,
    conta_id         uuid          NOT NULL REFERENCES conta (id),
    sessao_caixa_id  uuid          NOT NULL REFERENCES sessao_caixa (id),
    venda_id         uuid,
    tipo             varchar(20)   NOT NULL,
    valor            numeric(12,2) NOT NULL,
    motivo           varchar(120),
    criado_em        timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT movimento_caixa_tipo_valido
        CHECK (tipo IN ('VENDA', 'SANGRIA', 'SUPRIMENTO')),

    -- O valor gravado é sempre o que o operador digitou; quem carrega o sinal é o tipo.
    CONSTRAINT movimento_caixa_valor_nao_negativo
        CHECK (valor >= 0),

    -- RF14: motivo é obrigatório em SANGRIA e SUPRIMENTO. A regra também vive no domínio; aqui ela
    -- é a rede embaixo, para nenhum script de correção deixar uma retirada sem justificativa.
    CONSTRAINT movimento_caixa_motivo_obrigatorio
        CHECK (tipo = 'VENDA' OR motivo IS NOT NULL),

    -- venda_id é preenchido só quando tipo = VENDA. Só nesta direção: exigir venda_id em todo
    -- VENDA seria regra a mais do que o requisito pede.
    CONSTRAINT movimento_caixa_venda_so_em_venda
        CHECK (venda_id IS NULL OR tipo = 'VENDA')
);

-- Mesmo sendo membro de agregado, a tabela leva conta_id: toda tabela de negócio leva, e as três
-- exceções do sistema já estão todas criadas. A coluna direta é o que deixa o @TenantId filtrar
-- sem depender de JOIN com a raiz.
CREATE INDEX idx_movimento_caixa_conta ON movimento_caixa (conta_id);

-- O carregamento real é sempre pela raiz; este índice é o que sustenta esse caminho.
CREATE INDEX idx_movimento_caixa_sessao ON movimento_caixa (sessao_caixa_id);

COMMENT ON TABLE  movimento_caixa IS
    'Venda, sangria ou suprimento de uma sessão. Membro do agregado Caixa: sem repositório próprio.';
COMMENT ON COLUMN movimento_caixa.venda_id IS
    'Preenchido só quando tipo = VENDA. Sem FOREIGN KEY porque a tabela venda ainda não existe; referência entre agregados é por id de qualquer forma.';
COMMENT ON COLUMN movimento_caixa.valor IS
    'Sempre positivo. O sinal da soma assinada vem do tipo: SANGRIA subtrai, VENDA e SUPRIMENTO somam.';
COMMENT ON COLUMN movimento_caixa.motivo IS
    'RF14: obrigatório em SANGRIA e SUPRIMENTO. varchar(120) é o limite de descrição curta do schema: é texto que o operador escreve no balcão, não rótulo.';
