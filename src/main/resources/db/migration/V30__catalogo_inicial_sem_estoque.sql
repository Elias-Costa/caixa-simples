-- Marca como sem estoque o que a cafeteria prepara na hora (RF17, RF32).
--
-- A V4 cadastrou o catálogo inteiro da cafeteria como PRODUTO. Café, cappuccino, suco, sanduíche e
-- bolo em fatia são feitos no balcão, de ingredientes que ninguém conta por unidade: com o estoque
-- ligado, cada venda os deixava negativos, presos no alerta de estoque baixo, e cada venda sem rede
-- virava uma revisão de estoque negativo para conferir. SERVICO é exatamente o item que nunca gera
-- movimento de estoque, e a tela o chama de sem estoque, serviço ou preparado na hora.
--
-- Água mineral e Pão de queijo continuam com estoque: costumam ser comprados prontos ou congelados,
-- e contar o que sobra deles faz sentido.
--
-- Só o catálogo de referência muda, pelos ids fixos que a V4 deu às linhas. O produto já copiado
-- para uma Conta é dela, e o tipo não muda depois do cadastro: quem o quiser sem estoque o inativa
-- e cadastra de novo.
UPDATE modelo_produto SET tipo = 'SERVICO'
WHERE id IN (
    'a5e1c000-0000-4000-8000-000000000001', -- Café expresso
    'a5e1c000-0000-4000-8000-000000000002', -- Café coado
    'a5e1c000-0000-4000-8000-000000000003', -- Cappuccino
    'a5e1c000-0000-4000-8000-000000000004', -- Suco natural
    'a5e1c000-0000-4000-8000-000000000007', -- Sanduíche natural
    'a5e1c000-0000-4000-8000-000000000008'  -- Bolo em fatia
);
