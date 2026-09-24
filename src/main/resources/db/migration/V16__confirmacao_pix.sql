-- A correlação da cobrança permanece no histórico depois da confirmação financeira.
ALTER TABLE pagamento DROP CONSTRAINT pagamento_pix_cobranca_coerente;
ALTER TABLE pagamento ADD CONSTRAINT pagamento_pix_cobranca_coerente CHECK (
    (pix_txid IS NULL AND pix_chave_recebedora IS NULL AND pix_expira_em IS NULL
        AND pix_copia_e_cola IS NULL AND pix_estado IS NULL)
    OR
    (forma = 'PIX' AND pix_txid IS NOT NULL
        AND pix_chave_recebedora IS NOT NULL AND pix_expira_em IS NOT NULL
        AND pix_estado IN ('AGUARDANDO', 'DISPONIVEL', 'INCERTA')
        AND (pix_estado <> 'DISPONIVEL' OR pix_copia_e_cola IS NOT NULL))
);
