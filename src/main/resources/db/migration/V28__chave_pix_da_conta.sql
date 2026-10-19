-- A chave Pix em que a Conta recebe o Pix lançado à mão, e a cidade do recebedor.
--
-- O aparelho monta com as duas, e com o nome do negócio, o QR estático do Pix manual da venda e do
-- recebimento de fiado; o BR Code não se monta sem a cidade. Só o administrador as grava,
-- confirmando a senha atual. A chave fica normalizada pela aplicação, que confere o tipo, o formato
-- e os dígitos verificadores; o tamanho é o do maior e-mail aceito como chave. A cidade cabe o maior
-- nome de município, e o corte que o BR Code exige acontece só no QR.
--
-- As duas são gravadas juntas e apagadas juntas: o QR sem a cidade não se monta, e a cidade sem a
-- chave não serve para nada.
ALTER TABLE conta
    ADD COLUMN chave_pix varchar(77),
    ADD COLUMN cidade_pix varchar(60),
    ADD CONSTRAINT conta_chave_pix_com_cidade
        CHECK ((chave_pix IS NULL) = (cidade_pix IS NULL));

COMMENT ON COLUMN conta.chave_pix IS
    'A chave Pix normalizada em que a Conta recebe o Pix lançado à mão; nula quando não há.';
COMMENT ON COLUMN conta.cidade_pix IS
    'A cidade do recebedor no QR estático do Pix; nula junto com a chave.';
