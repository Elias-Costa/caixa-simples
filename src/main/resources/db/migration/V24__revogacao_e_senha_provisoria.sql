-- Revogação de token e senha provisória (RNF06).
--
-- O token dura um dia e se renova a cada resposta, então, sem revogação, um aparelho furtado que
-- continua com rede entra para sempre. O token passa a levar a versão de acesso do Usuário, e o
-- filtro que já lê o Usuário a cada requisição recusa o token cuja versão não é a do banco. Trocar a
-- senha, ter a senha redefinida ou pedir a saída de todos os aparelhos sobe a versão, e todos os
-- tokens emitidos antes deixam de valer na requisição seguinte. Um contador, e não um instante,
-- porque o instante de emissão do token tem precisão de segundos e um token emitido no mesmo segundo
-- da revogação ficaria ambíguo.
ALTER TABLE usuario
    ADD COLUMN versao_do_acesso integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN usuario.versao_do_acesso IS
    'RNF06: sobe a cada revogação; o token que leva outra versão recebe 401.';

-- A senha que outra pessoa escolheu (o mantenedor ao criar a Conta, o administrador ao criar ou
-- redefinir um operador) é conhecida por quem a escolheu, e o aplicativo pede a troca no primeiro
-- acesso. Toda credencial nasce assim, porque nenhuma é criada pela própria pessoa; as que já existem
-- foram escolhidas do mesmo jeito e migram marcadas. A troca pela própria pessoa desmarca.
ALTER TABLE credencial
    ADD COLUMN senha_provisoria boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN credencial.senha_provisoria IS
    'RNF06: a senha foi escolhida por outra pessoa e o aplicativo pede a troca; a troca pela própria pessoa desmarca.';
