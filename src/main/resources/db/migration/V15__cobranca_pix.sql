-- Correlação da cobrança Pix no membro Pagamento da Venda; não há repositório da parcela.
ALTER TABLE pagamento ADD COLUMN pix_txid varchar(35);
ALTER TABLE pagamento ADD COLUMN pix_chave_recebedora varchar(255);
ALTER TABLE pagamento ADD COLUMN pix_expira_em timestamptz;
ALTER TABLE pagamento ADD COLUMN pix_copia_e_cola text;
ALTER TABLE pagamento ADD COLUMN pix_estado varchar(20);

CREATE UNIQUE INDEX ux_pagamento_conta_pix_txid ON pagamento (conta_id, pix_txid)
    WHERE pix_txid IS NOT NULL;

ALTER TABLE pagamento ADD CONSTRAINT pagamento_pix_cobranca_coerente CHECK (
    (pix_txid IS NULL AND pix_chave_recebedora IS NULL AND pix_expira_em IS NULL
        AND pix_copia_e_cola IS NULL AND pix_estado IS NULL)
    OR
    (forma = 'PIX' AND status = 'PENDENTE' AND pix_txid IS NOT NULL
        AND pix_chave_recebedora IS NOT NULL AND pix_expira_em IS NOT NULL
        AND pix_estado IN ('AGUARDANDO', 'DISPONIVEL', 'INCERTA')
        AND (pix_estado <> 'DISPONIVEL' OR pix_copia_e_cola IS NOT NULL))
);
