-- Cliente como vertical slice.
--
-- Cobre RF03 (cadastro opcional de cliente, associável a uma venda), RF04 (edição) e RF05
-- (inativação sem exclusão).
--
-- Cliente não tem invariante a proteger, então não é agregado com estrutura: a tabela é chapada e
-- o caso de uso inteiro vive num arquivo só.

CREATE TABLE cliente (
    id         uuid         PRIMARY KEY,
    conta_id   uuid         NOT NULL REFERENCES conta (id),
    nome       varchar(120) NOT NULL,
    contato    varchar(180),
    ativo      boolean      NOT NULL DEFAULT true,
    criado_em  timestamptz  NOT NULL DEFAULT now()
);

-- Todo acesso a cliente passa pelo filtro de @TenantId; o índice sustenta esse filtro.
CREATE INDEX idx_cliente_conta ON cliente (conta_id);

-- Não existe índice único aqui, e a ausência é deliberada: nenhum requisito pede unicidade de
-- cliente. O `codigo` do produto só ganhou índice único porque o RF06 pede busca por código; dois
-- clientes homônimos no balcão são dois clientes, e recusar o segundo perderia o cadastro.

COMMENT ON TABLE  cliente IS
    'Cliente do negócio, opcional por venda (RF03). Vertical slice: sem membros de agregado.';
COMMENT ON COLUMN cliente.contato IS
    'Telefone ou e-mail, opcional. varchar(180) acompanha o limite do e-mail: o campo aceita e-mail, e um limite menor recusaria endereço legítimo. Em branco é gravado como NULL, para sem contato ter uma representação só.';
COMMENT ON COLUMN cliente.ativo IS
    'RF05: soft delete. Cliente inativado sai da listagem e continua no banco, para a venda antiga não perder a referência.';
