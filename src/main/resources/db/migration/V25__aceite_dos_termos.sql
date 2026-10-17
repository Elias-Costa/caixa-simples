-- O aceite dos termos de uso pelo administrador da Conta.
--
-- Os termos fixam o que o Caixa Simples faz com os dados que a Conta registra, e o aceite é a prova de
-- que a Conta deu essas instruções. Cada versão aceita é uma linha, para a prova de uma versão não
-- sumir quando a seguinte for aceita. Um aceite de qualquer administrador vale para a Conta inteira.
CREATE TABLE aceite_dos_termos (
    id          uuid         PRIMARY KEY,
    conta_id    uuid         NOT NULL REFERENCES conta (id),
    versao      varchar(20)  NOT NULL,
    aceito_em   timestamptz  NOT NULL,
    aceito_por  uuid         NOT NULL REFERENCES usuario (id),
    -- Uma linha por versão na Conta, mesmo que dois administradores aceitem juntos. O índice do
    -- par, com a Conta na frente, sustenta também o filtro de @TenantId.
    CONSTRAINT uq_aceite_dos_termos_versao_por_conta UNIQUE (conta_id, versao)
);

COMMENT ON TABLE aceite_dos_termos IS
    'Cada versão dos termos de uso aceita pela Conta, com quem e quando aceitou.';
COMMENT ON COLUMN aceite_dos_termos.versao IS
    'A data da versão dos termos, como o aplicativo a mostra e o servidor a confere.';
COMMENT ON COLUMN aceite_dos_termos.aceito_por IS
    'O administrador que aceitou; a linha do usuário continua mesmo depois da inativação.';
