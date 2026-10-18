-- Suspensão imediata do acesso de uma Conta, por uso ilegal ou ataque ao serviço.
--
-- Os termos de uso permitem suspender na hora o acesso de uma Conta nesses dois casos, com aviso ao
-- administrador. Quem suspende é o mantenedor, por uma execução avulsa da aplicação, e não por SQL à
-- mão. Com a coluna preenchida, o login e o filtro do token recusam todos os usuários da Conta; a
-- suspensão também sobe a versão de acesso de cada um, então o reverso, que só limpa a coluna, não
-- devolve o acesso a nenhum token emitido antes. Não é a suspensão por atraso da mensalidade, que
-- tira só os recursos pagos e se calcula pelo vencimento.
ALTER TABLE conta
    ADD COLUMN suspensa_em timestamptz;

COMMENT ON COLUMN conta.suspensa_em IS
    'O instante da suspensão imediata do acesso da Conta; nulo quando o acesso vale.';
