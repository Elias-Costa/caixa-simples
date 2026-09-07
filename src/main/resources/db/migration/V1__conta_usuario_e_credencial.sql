-- Etapas 0.7 e 0.8 do plano de implementacao: fundacao de tenancy e autenticacao.
--
-- Dicionario de dados: modelo-dados-caixa-simples.md §3.
-- Convencoes obrigatorias (rules/persistencia-e-migrations.md e decisions.md D12):
--   * chave primaria uuid gerada na aplicacao, nunca sequencia — permite criar registro offline
--     com identidade definitiva antes de qualquer sincronizacao (RNF01/RNF03);
--   * soft delete via `ativo`, nunca DELETE;
--   * nome de tabela e coluna em snake_case, em portugues, iguais aos do dicionario;
--   * indice em conta_id em toda tabela de negocio — todo acesso passa pelo filtro de tenant;
--   * texto curto varchar(60), texto medio varchar(120), dinheiro numeric(12,2), quantidade
--     numeric(12,3). Excecoes: email varchar(180) e senha_hash varchar(100).
--
-- Enums ficam como VARCHAR + CHECK, nao como tipo ENUM do Postgres (P5): adicionar valor a um tipo
-- ENUM exige ALTER TYPE fora de transacao, o que atrapalha migration e rollback. O
-- @Enumerated(STRING) do Hibernate mapeia direto para texto.

-- A conta e o tenant: o `id` dela e o proprio conta_id das outras tabelas, por isso esta e a unica
-- raiz de agregado sem coluna conta_id (e sem @TenantId).
CREATE TABLE conta (
    id                  uuid        PRIMARY KEY,
    nome_negocio        varchar(120) NOT NULL,
    tipo_negocio        varchar(60),
    plano               varchar(20) NOT NULL,
    estoque_habilitado  boolean     NOT NULL DEFAULT false,
    criado_em           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conta_plano_valido
        CHECK (plano IN ('GRATIS', 'CAIXA_SIMPLES', 'COMPLETO')),
    -- O uuid zerado e o tenant sentinela de TenantContext.SEM_TENANT, usado quando nao ha conta no
    -- contexto. Nenhuma conta real pode ocupa-lo: e o que garante que uma escrita sem tenant seja
    -- rejeitada pela foreign key de usuario.conta_id em vez de gravar dado orfao.
    CONSTRAINT conta_id_nao_reservado
        CHECK (id <> '00000000-0000-0000-0000-000000000000'::uuid)
);

COMMENT ON TABLE  conta IS 'Negocio contratante (tenant). Nao confundir com conta a pagar/receber.';
COMMENT ON COLUMN conta.tipo_negocio IS
    'Casa com modelo_produto.tipo_negocio para sugerir o catalogo inicial (RF32). Nulo = comeca em branco.';
COMMENT ON COLUMN conta.estoque_habilitado IS
    'RF17 — negocio de servico (salao, oficina) opera com o modulo de estoque desligado. Nasce falso (P4).';

-- Quem acessa o sistema dentro de uma conta. Nao guarda dado de autenticacao: e-mail e senha vivem
-- em `credencial` (D14d), entao esta entidade — que tem @TenantId — nao carrega segredo.
CREATE TABLE usuario (
    id          uuid         PRIMARY KEY,
    conta_id    uuid         NOT NULL REFERENCES conta (id),
    nome        varchar(120) NOT NULL,
    perfil      varchar(20)  NOT NULL,
    ativo       boolean      NOT NULL DEFAULT true,
    criado_em   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT usuario_perfil_valido
        CHECK (perfil IN ('ADMIN', 'OPERADOR'))
);

-- Todo acesso a usuario passa pelo filtro de @TenantId; o indice sustenta esse filtro.
CREATE INDEX idx_usuario_conta ON usuario (conta_id);

COMMENT ON COLUMN usuario.perfil IS
    'RF29/RF30 — OPERADOR nao ve relatorio consolidado nem configuracao da conta.';

-- Ponto de entrada do login (D9).
--
-- Esta tabela NAO tem @TenantId, e isso e o ponto dela: no login ainda nao existe tenant no
-- contexto, entao uma busca filtrada por conta devolveria vazio e a autenticacao nunca funcionaria.
--
-- Atencao a leitura da coluna `conta_id` aqui: ela e DADO, nao discriminador de tenant. E
-- justamente o que o login precisa descobrir para so entao resolver o resto sob filtro normal.
-- Esta e a terceira e ultima tabela do sistema fora do filtro, junto de `conta` e `modelo_produto`
-- (ver .claude/rules/multi-tenancy.md).
CREATE TABLE credencial (
    id          uuid         PRIMARY KEY,
    email       varchar(180) NOT NULL,
    senha_hash  varchar(100) NOT NULL,
    usuario_id  uuid         NOT NULL REFERENCES usuario (id),
    conta_id    uuid         NOT NULL REFERENCES conta (id),
    criado_em   timestamptz  NOT NULL DEFAULT now()
);

-- Um e-mail pertence a exatamente uma conta (P3), entao o unico e global, nao por conta. E o que
-- permite o login resolver e-mail -> conta sem tela intermediaria de escolha.
CREATE UNIQUE INDEX idx_credencial_email ON credencial (lower(email));

-- Um login por usuario.
CREATE UNIQUE INDEX idx_credencial_usuario ON credencial (usuario_id);

COMMENT ON TABLE  credencial IS
    'Login: mapeia e-mail para usuario e conta. Sem @TenantId — e consultada antes de existir tenant.';
COMMENT ON COLUMN credencial.senha_hash IS 'BCrypt. Nunca a senha em texto puro.';
COMMENT ON COLUMN credencial.conta_id   IS
    'Dado, nao discriminador de tenant: e o valor que o login descobre para popular o TenantContext.';
