-- Etapa 1.4 do plano de implementacao (passo R06 do roteiro): agregado Caixa.
--
-- Dicionario de dados: modelo-dados-caixa-simples.md §3 (SessaoCaixa, MovimentoCaixa) e §4.
-- Tipos de coluna: todos saem da tabela da D12 — nenhum tamanho inventado aqui.
--
-- Prepara RF13 (abertura), RF14 (sangria e suprimento) e RF15/RF16 (fechamento com conferencia).
-- A logica dos tres fica de fora de proposito: e R07 e R08. Aqui so nascem schema e mapeamento.
--
-- Vocabulario (CLAUDE.md, linguagem ubiqua): SANGRIA e retirada de dinheiro, SUPRIMENTO e reforco
-- de troco, e caixa e a sessao operacional entre abertura e fechamento — nao e o dinheiro nem o
-- sistema.

-- Raiz do agregado Caixa. A invariante que ela guarda (modelo-dados §4):
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

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres (P5).
    CONSTRAINT sessao_caixa_status_valido
        CHECK (status IN ('ABERTA', 'FECHADA'))
);

-- Todo acesso a sessao_caixa passa pelo filtro de @TenantId; o indice sustenta esse filtro.
CREATE INDEX idx_sessao_caixa_conta ON sessao_caixa (conta_id);

-- Nao existe indice unico de uma sessao aberta por vez, e a ausencia e deliberada: nenhum
-- documento diz que duas sessoes simultaneas sao proibidas, e a regra de abertura e do R07. Se
-- virar decisao la, entra em migration nova.

COMMENT ON TABLE  sessao_caixa IS
    'Sessao operacional do caixa entre abertura e fechamento. Raiz do agregado Caixa (modelo-dados §4).';
COMMENT ON COLUMN sessao_caixa.usuario_id IS
    'Quem abriu a sessao. Referencia entre agregados e por id, nunca objeto navegavel (modelo-dados §4).';
COMMENT ON COLUMN sessao_caixa.valor_fechamento_esperado IS
    'D21a — coluna viva, nao calculo do fechamento: nasce igual a valor_abertura e e reescrita a cada movimento, na mesma transacao. Mesmo padrao de produto.estoque_atual (modelo-dados §5), e a leitura literal do sempre reflete da §4.';
COMMENT ON COLUMN sessao_caixa.valor_fechamento_contado IS
    'RF15 — informado manualmente no fechamento; nulo enquanto a sessao esta ABERTA.';
COMMENT ON COLUMN sessao_caixa.diferenca IS
    'RF15 — esperado menos contado, gravado no fechamento (R08); nulo enquanto a sessao esta ABERTA.';

-- Membro do agregado: nunca lido nem escrito direto, sempre pela raiz (regra 3 do CLAUDE.md). Por
-- isso nao existe MovimentoCaixaRepository, e a entidade JPA correspondente e package-private.
--
-- Unifica venda, sangria e suprimento numa tabela so (modelo-dados §5): o fechamento vira uma soma
-- sobre esta tabela, sem juntar tres diferentes.
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

    -- D21b: o valor gravado e sempre o que o operador digitou; quem carrega o sinal e o tipo.
    CONSTRAINT movimento_caixa_valor_nao_negativo
        CHECK (valor >= 0),

    -- RF14 — o dicionario diz motivo obrigatorio se SANGRIA/SUPRIMENTO. A regra tambem vive no
    -- dominio a partir do R07; aqui ela e a rede embaixo, para nenhum script de correcao deixar
    -- uma retirada sem justificativa.
    CONSTRAINT movimento_caixa_motivo_obrigatorio
        CHECK (tipo = 'VENDA' OR motivo IS NOT NULL),

    -- O dicionario diz venda_id preenchido so quando tipo = VENDA. So nesta direcao: exigir
    -- venda_id em todo VENDA seria regra a mais do que esta escrito.
    CONSTRAINT movimento_caixa_venda_so_em_venda
        CHECK (venda_id IS NULL OR tipo = 'VENDA')
);

-- Mesmo sendo membro de agregado, a tabela leva conta_id: modelo-dados §5 manda a coluna em toda
-- tabela de negocio, e rules/multi-tenancy.md fecha a lista de excecoes em tres, todas ja
-- existentes. A coluna direta e o que deixa o @TenantId filtrar sem depender de JOIN com a raiz.
CREATE INDEX idx_movimento_caixa_conta ON movimento_caixa (conta_id);

-- O carregamento real e sempre pela raiz; este indice e o que sustenta esse caminho.
CREATE INDEX idx_movimento_caixa_sessao ON movimento_caixa (sessao_caixa_id);

COMMENT ON TABLE  movimento_caixa IS
    'Venda, sangria ou suprimento de uma sessao. Membro do agregado Caixa: sem repositorio proprio.';
COMMENT ON COLUMN movimento_caixa.venda_id IS
    'Preenchido so quando tipo = VENDA. Sem FOREIGN KEY porque a tabela venda so nasce no R11; referencia entre agregados e por id de qualquer forma (modelo-dados §4).';
COMMENT ON COLUMN movimento_caixa.valor IS
    'D21b — sempre positivo. O sinal da soma assinada vem do tipo: SANGRIA subtrai, VENDA e SUPRIMENTO somam.';
COMMENT ON COLUMN movimento_caixa.motivo IS
    'RF14 — obrigatorio em SANGRIA e SUPRIMENTO. varchar(120) e a categoria descricoes curtas da D12: e texto que o operador escreve no balcao, nao rotulo.';
