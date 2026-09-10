-- Agregado Venda: a venda, seus itens e seus pagamentos.
--
-- Prepara RF07 (carrinho com múltiplos itens), RF08 (desconto por item e por venda), RF09
-- (pagamento dividido entre formas) e RF12 (cancelamento). A lógica de todos fica de fora de
-- propósito: aqui só nascem schema e mapeamento, como a V5 fez para o caixa.

-- Raiz do agregado Venda. As invariantes que ela guarda:
--   valor_total = soma dos itens menos o desconto da venda;
--   numa venda CONCLUIDA, a soma dos pagamentos CONFIRMADO é igual a valor_total.
CREATE TABLE venda (
    id               uuid          PRIMARY KEY,
    conta_id         uuid          NOT NULL REFERENCES conta (id),
    sessao_caixa_id  uuid          NOT NULL REFERENCES sessao_caixa (id),
    usuario_id       uuid          NOT NULL REFERENCES usuario (id),
    cliente_id       uuid          REFERENCES cliente (id),
    status           varchar(20)   NOT NULL,
    valor_total      numeric(12,2) NOT NULL,
    valor_desconto   numeric(12,2) NOT NULL DEFAULT 0,
    criado_em        timestamptz   NOT NULL DEFAULT now(),

    -- Enum como VARCHAR + CHECK, nunca o tipo ENUM do Postgres.
    -- ABERTA é a comanda em montagem e também a venda que espera confirmação de pagamento; a venda
    -- só passa a CONCLUIDA quando os pagamentos confirmados cobrem o total (RF09).
    CONSTRAINT venda_status_valido
        CHECK (status IN ('ABERTA', 'CONCLUIDA', 'CANCELADA')),

    -- Valor zero vale (cortesia, brinde); negativo não é valor de venda.
    CONSTRAINT venda_valor_total_nao_negativo
        CHECK (valor_total >= 0),

    -- Desconto negativo seria acréscimo, e nenhum requisito pede acréscimo.
    CONSTRAINT venda_valor_desconto_nao_negativo
        CHECK (valor_desconto >= 0)
);

-- Todo acesso a venda passa pelo filtro de @TenantId; o índice sustenta esse filtro.
CREATE INDEX idx_venda_conta ON venda (conta_id);

-- Não há índice em sessao_caixa_id nem em criado_em: nenhuma consulta os usa ainda, e índice
-- nasce junto da consulta que o justifica, não antes. Mesma postura do histórico do caixa.

COMMENT ON TABLE  venda IS
    'Uma venda do balcão. Raiz do agregado Venda, com item_venda e pagamento como membros.';
COMMENT ON COLUMN venda.sessao_caixa_id IS
    'A sessão de caixa em que a venda foi feita. Referência entre agregados é por id, nunca objeto navegável.';
COMMENT ON COLUMN venda.usuario_id IS
    'O operador que realizou a venda.';
COMMENT ON COLUMN venda.cliente_id IS
    'RF03: cliente é opcional na venda. Nulo é a venda sem cliente identificado.';
COMMENT ON COLUMN venda.status IS
    'ABERTA enquanto a comanda está em montagem ou espera confirmação de pagamento; CONCLUIDA quando os pagamentos confirmados cobrem o total; CANCELADA pelo RF12.';
COMMENT ON COLUMN venda.valor_desconto IS
    'RF08: desconto sobre o total da venda, além do desconto de cada item. Sem desconto é zero, não nulo, para nenhum leitor precisar tratar ausência.';

-- Membro do agregado: nunca lido nem escrito direto, sempre pela raiz. Por isso não existe
-- ItemVendaRepository, e a entidade JPA correspondente é package-private.
CREATE TABLE item_venda (
    id              uuid          PRIMARY KEY,
    conta_id        uuid          NOT NULL REFERENCES conta (id),
    venda_id        uuid          NOT NULL REFERENCES venda (id),
    produto_id      uuid          NOT NULL REFERENCES produto (id),
    quantidade      numeric(12,3) NOT NULL,
    preco_unitario  numeric(12,2) NOT NULL,
    desconto        numeric(12,2) NOT NULL DEFAULT 0,
    criado_em       timestamptz   NOT NULL DEFAULT now(),

    -- Item com quantidade zero não é item: nada foi vendido.
    CONSTRAINT item_venda_quantidade_positiva
        CHECK (quantidade > 0),

    -- Mesma regra do preço do produto: zero vale, negativo não.
    CONSTRAINT item_venda_preco_unitario_nao_negativo
        CHECK (preco_unitario >= 0),

    CONSTRAINT item_venda_desconto_nao_negativo
        CHECK (desconto >= 0)
);

-- Mesmo sendo membro de agregado, a tabela leva conta_id: toda tabela de negócio leva, e as três
-- exceções do sistema já estão todas criadas. A coluna direta é o que deixa o @TenantId filtrar
-- sem depender de JOIN com a raiz.
CREATE INDEX idx_item_venda_conta ON item_venda (conta_id);

-- O carregamento real é sempre pela raiz; este índice é o que sustenta esse caminho.
CREATE INDEX idx_item_venda_venda ON item_venda (venda_id);

COMMENT ON TABLE  item_venda IS
    'Um produto ou serviço dentro de uma venda. Membro do agregado Venda: sem repositório próprio.';
COMMENT ON COLUMN item_venda.produto_id IS
    'Referência entre agregados, por id. O produto pode ser inativado depois (RF05); a venda continua apontando para ele.';
COMMENT ON COLUMN item_venda.quantidade IS
    'Três casas porque venda fracionada é real: 0,750 kg.';
COMMENT ON COLUMN item_venda.preco_unitario IS
    'Cópia do preço do produto no momento da venda, nunca leitura viva: reajustar o produto não pode alterar o valor de uma venda passada.';
COMMENT ON COLUMN item_venda.desconto IS
    'RF08: desconto deste item. Sem desconto é zero, não nulo. Como ele entra na conta do total é regra da montagem da venda, no domínio.';
COMMENT ON COLUMN item_venda.criado_em IS
    'Dá ordem de leitura aos itens, na sequência em que entraram na comanda. Empate de instante fica sem ordem definida, e isso é aceitável.';

-- Membro do agregado, pelo mesmo motivo de item_venda: sem PagamentoRepository.
--
-- Entidade própria, e não colunas na venda, porque uma venda pode ser dividida entre formas de
-- pagamento (RF09): metade em dinheiro e metade no cartão são dois registros desta tabela.
CREATE TABLE pagamento (
    id         uuid          PRIMARY KEY,
    conta_id   uuid          NOT NULL REFERENCES conta (id),
    venda_id   uuid          NOT NULL REFERENCES venda (id),
    forma      varchar(20)   NOT NULL,
    valor      numeric(12,2) NOT NULL,
    status     varchar(20)   NOT NULL,
    criado_em  timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pagamento_forma_valida
        CHECK (forma IN ('DINHEIRO', 'PIX', 'CARTAO')),

    -- PENDENTE existe para a cobrança de Pix gerada por um provedor, cuja confirmação chega
    -- depois. O que o operador lança à mão nasce CONFIRMADO.
    CONSTRAINT pagamento_status_valido
        CHECK (status IN ('PENDENTE', 'CONFIRMADO', 'RECUSADO')),

    CONSTRAINT pagamento_valor_nao_negativo
        CHECK (valor >= 0)
);

CREATE INDEX idx_pagamento_conta ON pagamento (conta_id);
CREATE INDEX idx_pagamento_venda ON pagamento (venda_id);

-- Não há coluna para o identificador de transação do provedor de Pix (RF25), e a ausência é
-- deliberada: nenhuma forma de pagamento implementada produz esse valor, já que dinheiro não tem
-- e Pix e cartão lançados à mão tampouco. A coluna entra em migration nova, junto do primeiro
-- provedor de verdade, com o tamanho que o identificador real tiver.

COMMENT ON TABLE  pagamento IS
    'Uma parcela do pagamento de uma venda, numa forma só. Membro do agregado Venda: sem repositório próprio.';
COMMENT ON COLUMN pagamento.valor IS
    'O valor desta parcela, não o total da venda. A soma das parcelas confirmadas é o que conclui a venda (RF09).';
COMMENT ON COLUMN pagamento.status IS
    'PENDENTE só para cobrança gerada por provedor de Pix, cuja confirmação chega depois. Lançamento à mão nasce CONFIRMADO.';
COMMENT ON COLUMN pagamento.criado_em IS
    'Quando a parcela foi lançada. Dá ordem de leitura e vai servir de referência para a confirmação que chega depois.';

-- A V5 criou movimento_caixa.venda_id sem FOREIGN KEY apenas porque a tabela venda ainda não
-- existia. Agora existe, e a FK entra aqui, em versão nova, porque migration commitada é imutável.
-- Continua sendo referência entre agregados por id: a FK protege o dado no banco, não cria objeto
-- navegável no código.
ALTER TABLE movimento_caixa
    ADD CONSTRAINT movimento_caixa_venda_fk FOREIGN KEY (venda_id) REFERENCES venda (id);

-- O comentário gravado pela V5 dizia que não havia FK; reemitido para o banco contar a verdade.
COMMENT ON COLUMN movimento_caixa.venda_id IS
    'Preenchido só quando tipo = VENDA. Referência entre agregados é por id; a FOREIGN KEY existe desde a V7, quando a tabela venda passou a existir.';
