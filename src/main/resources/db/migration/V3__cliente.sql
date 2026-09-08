-- Etapa 1.2 do plano de implementacao (passo R04 do roteiro): Cliente como vertical slice.
--
-- Dicionario de dados: modelo-dados-caixa-simples.md §3.
-- Tipos de coluna: tabela da D12, com a excecao de `contato` registrada na D19b.
--
-- Cobre RF03 (cadastro opcional de cliente, associavel a uma venda), RF04 (edicao) e RF05
-- (inativacao sem exclusao).
--
-- Cliente nao tem invariante a proteger, entao nao e agregado com estrutura: a tabela e chapada e
-- o caso de uso inteiro vive num arquivo so (arquitetura §1 e §2, P7).

CREATE TABLE cliente (
    id         uuid         PRIMARY KEY,
    conta_id   uuid         NOT NULL REFERENCES conta (id),
    nome       varchar(120) NOT NULL,
    contato    varchar(180),
    ativo      boolean      NOT NULL DEFAULT true,
    criado_em  timestamptz  NOT NULL DEFAULT now()
);

-- Todo acesso a cliente passa pelo filtro de @TenantId; o indice sustenta esse filtro.
CREATE INDEX idx_cliente_conta ON cliente (conta_id);

-- Nao existe indice unico aqui, e a ausencia e deliberada: nenhum requisito pede unicidade de
-- cliente. O `codigo` do produto so ganhou indice unico porque o RF06 pede busca por codigo (D11);
-- dois clientes homonimos no balcao sao dois clientes, e recusar o segundo perderia o cadastro.

COMMENT ON TABLE  cliente IS
    'Cliente do negocio, opcional por venda (RF03). Vertical slice: sem membros de agregado.';
COMMENT ON COLUMN cliente.contato IS
    'D19a/D19b — telefone ou e-mail, opcional. varchar(180) e a folga do email (P2): o campo aceita e-mail, e um limite menor recusaria endereco legitimo. Em branco e gravado como NULL, para sem contato ter uma representacao so.';
COMMENT ON COLUMN cliente.ativo IS
    'RF05 — soft delete. Cliente inativado sai da listagem e continua no banco, para a venda antiga nao perder a referencia.';
