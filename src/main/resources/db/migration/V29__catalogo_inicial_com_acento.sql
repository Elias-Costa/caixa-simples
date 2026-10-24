-- Acentua os nomes do catálogo inicial da cafeteria (RF32).
--
-- A V4 gravou os nomes sem acento, e eles iam assim para o balcão e para o comprovante do cliente:
-- Cafe expresso, Pao de queijo. A V4 é imutável, então a correção vem aqui, pelos ids fixos que ela
-- deu às linhas justamente para uma correção futura mirar a linha certa.
--
-- Só o catálogo de referência muda. O produto já copiado para uma Conta é dela, e o dono pode tê-lo
-- renomeado; a Conta que nasceu antes desta versão corrige o nome pela tela de produtos.
UPDATE modelo_produto SET nome = 'Café expresso'     WHERE id = 'a5e1c000-0000-4000-8000-000000000001';
UPDATE modelo_produto SET nome = 'Café coado'        WHERE id = 'a5e1c000-0000-4000-8000-000000000002';
UPDATE modelo_produto SET nome = 'Água mineral'      WHERE id = 'a5e1c000-0000-4000-8000-000000000005';
UPDATE modelo_produto SET nome = 'Pão de queijo'     WHERE id = 'a5e1c000-0000-4000-8000-000000000006';
UPDATE modelo_produto SET nome = 'Sanduíche natural' WHERE id = 'a5e1c000-0000-4000-8000-000000000007';
