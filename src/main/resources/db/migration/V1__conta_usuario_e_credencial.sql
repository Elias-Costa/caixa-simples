-- Fundação de multi-tenancy e autenticação: conta, usuário e credencial.
--
-- Convenções que valem para toda tabela deste schema:
--   * chave primária uuid gerada na aplicação, nunca sequência, o que permite criar registro
--     offline com identidade definitiva antes de qualquer sincronização (RNF01/RNF03);
--   * soft delete via `ativo`, nunca DELETE;
--   * nome de tabela e de coluna em snake_case, em português;
--   * índice em conta_id em toda tabela de negócio, já que todo acesso passa pelo filtro de tenant;
--   * texto curto varchar(60), texto médio varchar(120), dinheiro numeric(12,2), quantidade
--     numeric(12,3). Exceções: email varchar(180) e senha_hash varchar(100).
--
-- Enums ficam como VARCHAR + CHECK, não como tipo ENUM do Postgres: acrescentar valor a um tipo
-- ENUM exige ALTER TYPE fora de transação, o que atrapalha migration e rollback. O
-- @Enumerated(STRING) do Hibernate mapeia direto para texto.

-- A conta é o tenant: o `id` dela é o próprio conta_id das outras tabelas, e por isso esta é a
-- única raiz de agregado sem coluna conta_id, e sem @TenantId.
CREATE TABLE conta (
    id                  uuid        PRIMARY KEY,
    nome_negocio        varchar(120) NOT NULL,
    tipo_negocio        varchar(60),
    plano               varchar(20) NOT NULL,
    estoque_habilitado  boolean     NOT NULL DEFAULT false,
    criado_em           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conta_plano_valido
        CHECK (plano IN ('GRATIS', 'CAIXA_SIMPLES', 'COMPLETO')),
    -- O uuid zerado é o tenant sentinela de TenantContext.SEM_TENANT, usado quando não há conta no
    -- contexto. Nenhuma conta real pode ocupá-lo: é o que garante que uma escrita sem tenant seja
    -- rejeitada pela foreign key de usuario.conta_id em vez de gravar dado órfão.
    CONSTRAINT conta_id_nao_reservado
        CHECK (id <> '00000000-0000-0000-0000-000000000000'::uuid)
);

COMMENT ON TABLE  conta IS 'Negócio contratante (tenant). Não confundir com conta a pagar ou receber.';
COMMENT ON COLUMN conta.tipo_negocio IS
    'Casa com modelo_produto.tipo_negocio para sugerir o catálogo inicial (RF32). Nulo começa em branco.';
COMMENT ON COLUMN conta.estoque_habilitado IS
    'RF17: negócio de serviço (salão, oficina) opera com o módulo de estoque desligado. Nasce falso.';

-- Quem acessa o sistema dentro de uma conta. Não guarda dado de autenticação: e-mail e senha vivem
-- em `credencial`, então esta entidade, que tem @TenantId, não carrega segredo.
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

-- Todo acesso a usuário passa pelo filtro de @TenantId; o índice sustenta esse filtro.
CREATE INDEX idx_usuario_conta ON usuario (conta_id);

COMMENT ON COLUMN usuario.perfil IS
    'RF29/RF30: OPERADOR não vê relatório consolidado nem configuração da conta.';

-- Ponto de entrada do login.
--
-- Esta tabela NÃO tem @TenantId, e isso é o ponto dela: no login ainda não existe tenant no
-- contexto, então uma busca filtrada por conta devolveria vazio e a autenticação nunca funcionaria.
--
-- Atenção à leitura da coluna `conta_id` aqui: ela é DADO, não discriminador de tenant. É
-- justamente o valor que o login precisa descobrir para só então resolver o resto sob filtro
-- normal. Esta é a terceira e última tabela do sistema fora do filtro, junto de `conta` e
-- `modelo_produto`, e nenhuma outra entra nessa lista.
CREATE TABLE credencial (
    id          uuid         PRIMARY KEY,
    email       varchar(180) NOT NULL,
    senha_hash  varchar(100) NOT NULL,
    usuario_id  uuid         NOT NULL REFERENCES usuario (id),
    conta_id    uuid         NOT NULL REFERENCES conta (id),
    criado_em   timestamptz  NOT NULL DEFAULT now()
);

-- Um e-mail pertence a exatamente uma conta, então o índice único é global e não por conta. É o
-- que permite o login resolver e-mail para conta sem tela intermediária de escolha.
CREATE UNIQUE INDEX idx_credencial_email ON credencial (lower(email));

-- Um login por usuário.
CREATE UNIQUE INDEX idx_credencial_usuario ON credencial (usuario_id);

COMMENT ON TABLE  credencial IS
    'Login: mapeia e-mail para usuário e conta. Sem @TenantId, por ser consultada antes de existir tenant.';
COMMENT ON COLUMN credencial.senha_hash IS 'BCrypt. Nunca a senha em texto puro.';
COMMENT ON COLUMN credencial.conta_id   IS
    'Dado, não discriminador de tenant: é o valor que o login descobre para popular o TenantContext.';
