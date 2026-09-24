# Caixa Simples

Sistema de caixa e ponto de venda multi-tenant para pequenos negócios, construído como monolito
modular em Java com Spring Boot e Spring Modulith.

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?logo=springboot&logoColor=white)
![Spring Modulith](https://img.shields.io/badge/Spring%20Modulith-2.1.1-6DB33F?logo=spring&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)
![Testes](https://img.shields.io/badge/testes-Testcontainers-2496ED?logo=docker&logoColor=white)
![Estado](https://img.shields.io/badge/estado-em%20desenvolvimento-orange)
![Licença](https://img.shields.io/badge/licen%C3%A7a-todos%20os%20direitos%20reservados-lightgrey)

> **Vitrine técnica.** Este repositório existe para que o código possa ser lido e avaliado. Ele não
> traz instruções de execução, configuração de ambiente nem provisionamento: o objetivo é mostrar
> como o sistema foi projetado e escrito, não distribuí-lo. Ver [Licença](#licença).
>
> O sistema está em construção. A seção [Estado atual](#estado-atual) separa, sem eufemismo, o que
> já existe em código do que ainda é desenho, para que a leitura das seções seguintes tenha a régua
> certa.

---

## Sumário

- [Caixa Simples](#caixa-simples)
  - [Sumário](#sumário)
  - [Sobre o projeto](#sobre-o-projeto)
  - [Estado atual](#estado-atual)
  - [Por onde começar a leitura](#por-onde-começar-a-leitura)
  - [Arquitetura](#arquitetura)
    - [Camadas dentro do módulo](#camadas-dentro-do-módulo)
    - [Padrões aplicados](#padrões-aplicados)
    - [Padrões decididos, ainda não escritos](#padrões-decididos-ainda-não-escritos)
  - [Decisões estruturais](#decisões-estruturais)
  - [Segurança e isolamento](#segurança-e-isolamento)
  - [Modelo de dados](#modelo-de-dados)
    - [Agregados](#agregados)
    - [Detalhes de modelagem que valem nota](#detalhes-de-modelagem-que-valem-nota)
  - [Qualidade e testes](#qualidade-e-testes)
  - [Stack](#stack)
  - [Estrutura do repositório](#estrutura-do-repositório)
  - [Autor](#autor)
  - [Licença](#licença)

---

## Sobre o projeto

Um mercadinho, uma cafeteria e um salão precisam da mesma coisa: registrar a venda, controlar o
caixa e saber quanto entrou no dia. Cada um, porém, chama os itens de um jeito e guarda atributos
diferentes deles. A saída comum do mercado é um sistema por segmento, ou um sistema genérico que
obriga todo mundo a caber no mesmo cadastro.

Três problemas técnicos definem o desenho deste sistema:

1. **Catálogo genérico sem migração por cliente.** Colunas fixas para o que é universal (nome,
   preço, categoria, unidade) e uma coluna `JSONB` com índice GIN para os atributos que variam por
   tipo de negócio. Sem tabela por segmento e sem EAV.
2. **Operação offline confiável.** Registrar venda, cadastrar item e abrir ou fechar o caixa
   precisam continuar funcionando sem internet. Isso empurra decisões para dentro do modelo:
   identidade gerada no cliente, sincronização idempotente, sessão que resiste à falta de rede.
3. **Isolamento entre contas como requisito de segurança**, não como detalhe de implementação. Dado
   de um negócio nunca pode aparecer para outro, e isso precisa ser provado por teste.

O sistema é um monolito modular preparado para receber módulos verticais (agenda, comanda, ficha de
cliente) sem redesenhar o núcleo.

## Estado atual

O núcleo transacional é construído módulo a módulo, e cada um fecha com a suíte verde antes do
próximo começar. Os oito módulos estão declarados e têm suas fronteiras verificadas desde o
primeiro dia; sete já têm código de negócio dentro. O aplicativo, um PWA em `frontend/`, tem
login, sessão no dispositivo, navegação por perfil e abertura sem rede. As telas de
produto, cliente, caixa, Venda, faturamento, usuários, configuração e estoque já operam sobre a API.

| Módulo | Estado | O que existe hoje |
|---|---|---|
| `shared` | Implementado | `Money`, `ContaId`, `FusoDeReferencia` e os dois contextos da requisição: `TenantContext`, a conta em operação, e `UsuarioContext`, quem está operando e com que perfil, com as duas perguntas de autorização que todo caso de uso restrito faz na primeira linha. E o tratamento transversal de erro da API: toda resposta de erro sai em Problem Details, a recusa por perfil vira 403, argumento que o domínio rejeita vira 400 e estado que o agregado rejeita vira 409. E a entrega do aplicativo: o shell do PWA sai daqui, com o fallback que devolve a mesma página para qualquer rota do cliente e nunca para a API |
| `contas` | Implementado | `Conta`, `Usuario`, `Credencial`, login por JWT, política de senha, provisionamento de conta, a pergunta que os outros módulos fazem à conta em operação, como se o controle de estoque está ligado, e a gestão de usuários: o administrador cria operador ou administrador com login próprio, lista e inativa pela API e pelo PWA, com mais de um usuário só no plano mais alto e a conta nunca sem administrador. A configuração liga o controle de estoque e só o desliga antes do primeiro movimento; o menu reage imediatamente à mudança. O filtro que resolve o token lê o usuário no banco a cada requisição, então inativar vale na requisição seguinte, e o perfil que vale é o do banco, não o do token. O primeiro login do administrador marca a Conta e publica o primeiro acesso na mesma transação que copia o catálogo sugerido. Quem está autenticado pergunta em `/api/auth/eu` e recebe o próprio nome, o negócio, o tipo dele e se o estoque está ligado: é o cabeçalho de toda tela |
| `cadastro` | Implementado | `Produto` com atributos `JSONB`, busca por nome ou código para o balcão, `Cliente` como vertical slice, catálogo sugerido por tipo de negócio e copiado no primeiro login do administrador, e o agregado inteiro: `MovimentoEstoque` como membro, com a baixa por venda, o estorno por cancelamento e o ajuste manual gravando movimento e saldo na mesma transação, o estoque mínimo de cada produto e a resposta de quais estão com estoque baixo. A API e o PWA permitem cadastrar, editar, inativar e listar produtos ativos, buscar produto para o PDV e cadastrar, editar, inativar, reativar e listar clientes. Só o administrador escreve produto; os dois perfis consultam produtos e cadastram clientes no balcão |
| `caixa` | Implementado | `SessaoCaixa`: abertura, sangria, suprimento, fechamento com conferência, histórico por operador e por dia, consulta da sessão aberta do usuário atual e extrato de uma sessão com a Venda de origem nos movimentos. A API e o PWA permitem operar e conferir o caixa, com o esperado visível antes de informar o contado. O dinheiro em espécie de cada venda concluída entra na gaveta por evento, uma vez só; o cancelamento o estorna. O caixa é de quem o abriu: o operador só lança, fecha e consulta a própria sessão e só pede o próprio histórico; o administrador toca qualquer sessão da conta, inclusive para fechar o caixa de outro operador |
| `pagamentos` | Implementado para cobrança, confirmação e cancelamento Pix | Strategy por forma: dinheiro com troco, cartão manual, FIADO pendente e Pix integrado. `PixGateway` usa um adapter Efí por Conta para criar, remover e reconsultar a cobrança pelo `txid`; só o Pix comprovado na consulta quita a parcela (RF25). Credenciais de homologação ainda não foram exercitadas |
| `vendas` | Implementado para o PDV online | Agregado `Venda`, com `ItemVenda`, `Pagamento` e `Recebimento` como membros. A comanda copia o preço, permite divisão de formas e desconto pelo ADMIN. O Cliente ativo pode ser vinculado à Venda; só o ADMIN registra FIADO e conclui a Venda com sua parcela pendente. Qualquer perfil recebe a dívida na própria SessaoCaixa, em lançamentos parciais; o saldo vem das parcelas e dos recebimentos. O comprovante da Venda mostra o valor pendente e cada recebimento tem comprovante próprio (RF33). Confirmação Pix reconsultada conclui uma vez quando a cobertura e a SessaoCaixa permitem; Pix tardio fica visível para conciliação do ADMIN. O cancelamento recusa o Pix pendente só após remoção comprovada no PSP e mantém o Pix pago para devolução manual. Conclusão e cancelamento publicam eventos para caixa e estoque; recebimento publica evento para caixa. O operador só altera suas Vendas, salvo o recebimento de fiado da Conta |
| `estoque` | Implementado para o PWA online | o ouvinte da venda concluída: quando a conta ligou o controle de estoque, cada produto vendido vira uma baixa, pedida ao cadastro, dono do agregado; serviço no meio dos itens não gera nada, e o evento entregue de novo não baixa em dobro. E os casos de uso que uma pessoa aciona: ajuste manual com motivo obrigatório, perda, quebra ou contagem, com a diferença carregando o sinal; estoque mínimo por produto; e o alerta de estoque baixo como consulta, a lista dos produtos ativos no mínimo ou abaixo. A API e o PWA listam saldos e mínimos, ajustam o saldo, definem o mínimo e mostram o alerta; a contagem na tela mostra a diferença antes de gravar. A Conta com controle desligado recebe 409 e o operador, 403. O ouvinte da venda cancelada devolve cada produto que a venda baixou, uma vez só |
| `relatorios` | Implementado | Faturamento do dia e de um período, produtos mais vendidos e fluxo de caixa da gaveta. Venda concluída com FIADO conta no faturamento no dia da conclusão; recebimento em dinheiro conta no fluxo no dia da entrada, sem faturar outra vez (RF33). Filtros combináveis de faturamento por forma e operador incluem a parcela FIADO pendente. O módulo lê mapeamentos imutáveis de venda, item, produto, pagamento e movimento de caixa, e os três relatórios são do administrador |

**Schema.** Dezesseis migrations Flyway, de `V1` a `V16`: conta, usuário e credencial; produto; cliente;
catálogo de referência; o agregado de caixa, com a regra de uma sessão aberta por operador; o
agregado de venda, com a venda, seus itens e seus pagamentos; o outbox de eventos de domínio do
Spring Modulith, cujo DDL foi gerado a partir da entidade do framework em vez de escrito de
memória; o movimento de estoque, o membro que o agregado de produto esperava desde a segunda; o
estoque mínimo de cada produto, o limiar do alerta de estoque baixo; o estorno no caixa, o
quarto tipo de movimento, com as restrições da quinta recriadas para o receber; e o que o
comprovante precisava e a venda não guardava, o troco de cada parcela e o instante da conclusão;
e a marca do catálogo inicial aplicado na Conta, gravada junto da cópia dos itens. A `V14`
inclui FIADO, `recebimento` e o movimento RECEBIMENTO da gaveta. A `V15` guarda `txid`, chave
recebedora, vencimento, copia e cola e estado da cobrança Pix na parcela da Venda. A `V16`
conserva esses dados quando a parcela integrada passa a CONFIRMADO ou RECUSADO.

**Superfície HTTP.** A autenticação usa `POST /api/auth/login`, que devolve o
token, e `GET /api/auth/eu`, que diz quem está autenticado e em que negócio, para o cabeçalho de
toda tela, e que serve também de recurso protegido contra o qual verificar, por HTTP, que
requisição sem token é recusada, que o tenant vem do claim e não do pedido, e que o perfil é o do
banco: um usuário inativado recebe 401 na requisição seguinte, com o token que já tinha. O contrato
de erro já está fixado para todos os endpoints que virão: toda resposta de erro é Problem Details
(RFC 9457), produzida por um tratador transversal em `shared` que estende o do próprio Spring MVC,
para o cliente receber uma forma só venha o erro do framework ou da aplicação. A recusa por perfil
vira 403; argumento que o domínio rejeita, 400, o mesmo da validação de corpo, que ainda lista a
mensagem por campo; estado que o agregado rejeita, 409; erro inesperado, 500 sem a mensagem
interna; e o não encontrado de cada módulo é traduzido em 404 no pacote `web` do próprio módulo.
Valor monetário viaja como número, e a conversão para `Money` é escrita no controller. O cadastro
oferece `GET` e `POST /api/produtos`, `PUT /api/produtos/{id}` e
`POST /api/produtos/{id}/inativar`; para clientes, `GET` e `POST /api/clientes`,
`GET /api/clientes/inativos`, `PUT /api/clientes/{id}`,
`POST /api/clientes/{id}/inativar` e `POST /api/clientes/{id}/reativar`.
O PDV busca produto em `GET /api/produtos/busca?termo=...` e usa `GET` e `POST /api/vendas`
para listar as vendas de uma SessaoCaixa e iniciar uma comanda. `GET /api/vendas/{id}` devolve
itens, total e parcelas; `POST /api/vendas/{id}/itens` e
`DELETE /api/vendas/{id}/itens/{itemId}` montam a comanda;
`PUT /api/vendas/{id}/desconto` é só do administrador;
`POST /api/vendas/{id}/pagamentos` devolve o troco para as formas sem Pix integrado;
`POST /api/vendas/{id}/pagamentos/pix` recebe `tentativaId` e valor, devolve parcela PENDENTE e
estado da cobrança; o PWA mostra QR Code e copia e cola; `GET /api/vendas/{id}` permite retomar
o mesmo QR e não expõe outra Conta;
`POST /api/webhooks/pix/{identificador}/pix?hmac=...` recebe o aviso do PSP sem JWT de operador,
sob mTLS, identifica a configuração da Conta pelo identificador opaco e segredo, e reconsulta a
cobrança antes de alterar a Venda. `GET /api/vendas/conciliacoes-pix` mostra somente ao ADMIN os
Pix pagos que chegaram após a SessaoCaixa fechar ou após a Venda ser cancelada, sem apresentar
essas Vendas como concluídas;
`PUT /api/vendas/{id}/cliente` vincula Cliente ativo;
`POST /api/vendas/{id}/conclusao` e `POST /api/vendas/{id}/cancelamento` mudam o status;
no cancelamento com Pix pendente, o servidor pede remoção à Efí e reconsulta antes de gravar,
respondendo 503 quando não consegue comprovar que a cobrança parou de aceitar pagamento; repetir
um cancelamento de Venda com Pix integrado já cancelada não repete o efeito;
`GET /api/vendas/{id}/comprovante` traz o dado não fiscal para a tela. Para RF33,
`GET /api/fiado/clientes/{clienteId}/saldo` e `GET /api/fiado/dividas` mostram a dívida;
`POST /api/vendas/{id}/recebimentos` registra cada entrada e
`GET /api/vendas/{id}/recebimentos/{recebimentoId}/comprovante` traz seu comprovante.
O caixa oferece `GET` e `POST /api/caixa/sessoes`,
`GET /api/caixa/sessoes/aberta`, `GET /api/caixa/sessoes/{id}`,
`POST /api/caixa/sessoes/{id}/sangrias`,
`POST /api/caixa/sessoes/{id}/suprimentos` e
`POST /api/caixa/sessoes/{id}/fechamento`. O histórico recebe o dia e, opcionalmente, o operador;
a consulta por id devolve o extrato, e as listas carregam só os totais.
O faturamento usa `GET /api/relatorios/faturamento/dia?dia=...` e
`GET /api/relatorios/faturamento?inicio=...&fim=...`; os dois devolvem o período, o total e
a quantidade de vendas, e recusam o OPERADOR com 403.
Usuários usam `GET` e `POST /api/usuarios` e
`POST /api/usuarios/{id}/inativar`; a lista não expõe e-mail, a criação num plano sem
multiusuário responde 409 e a inativação do último administrador também. A configuração usa
`GET` e `PUT /api/conta/configuracao`, com `estoqueHabilitado` booleano; depois do primeiro
movimento de estoque, a tentativa de desligar responde 409. Essas rotas são só do administrador.
O estoque oferece `GET /api/estoque/produtos` para os saldos e mínimos,
`GET /api/estoque/baixo` para o alerta, `POST /api/estoque/produtos/{id}/ajustes`
para lançar uma diferença com motivo e `PUT /api/estoque/produtos/{id}/minimo` para o limiar.
As quatro rotas exigem ADMIN e controle ligado; produto de outra Conta recebe 404.
A escrita de produto por operador responde 403; id de outra Conta responde 404.
Os casos de uso de produto, cliente, caixa, pagamento, venda, estoque, relatório e usuário são
exercitados por teste de integração, inclusive a autorização por perfil, que é
verificada dentro de cada caso de uso e não por rota: a camada `web` de cada módulo nasce junto
com a tela do PWA que vai consumi-la, para o contrato HTTP ser exercitado pela interface.

**Configuração do webhook Pix (RF25, RNF05).** Cada Conta recebedora usa as variáveis de
credencial Efí com prefixo `CAIXA_SIMPLES_EFI_<UUID_DA_CONTA_SEM_HIFENS>_`, mais `WEBHOOK_ID`
(identificador opaco exclusivo) e `WEBHOOK_SECRET` (valor aleatório forte). Registre na Efí uma
URL HTTPS no formato `/api/webhooks/pix/{identificador}/pix?hmac={segredo}&ignorar=`; `ignorar=`
evita que o PSP acrescente outro `/pix`. Nenhum desses valores é enviado pelo navegador. O
servidor TLS que entrega a rota precisa solicitar e validar certificado de cliente contra a
cadeia oficial da Efí e disponibilizá-lo como atributo servlet
`jakarta.servlet.request.X509Certificate`; sem HTTPS e certificado, a aplicação responde 403.
O backend não pode ser acessado por um caminho que contorne essa validação. A validação real
do webhook depende de credenciais de homologação e de endereço HTTPS alcançável; os testes
exercitam o callback simulado e a consulta por gateway de teste.

**Aplicativo.** Fora de `/api` o servidor entrega o PWA, público por natureza: a página, os
scripts com hash no nome, o manifest, o service worker e os ícones, copiados do build do
`frontend/` para dentro do jar, na mesma origem da API, sem CORS. Qualquer rota do cliente pedida
direto ao servidor recebe a mesma página, e o roteador do cliente escolhe a tela; uma rota da API
que não existe nunca recebe a página, e sim 401 sem token e 404 com token. Todo dado sai por
`/api`, com token. O aplicativo tem hoje o login, a sessão guardada no dispositivo, o shell com o
nome do negócio e de quem opera, a navegação por perfil e as telas de produtos, clientes, caixa,
Venda, fiado e faturamento. Nelas se listam e alteram os cadastros, com pares livres de chave e valor para os atributos do
produto. No caixa, quem opera abre a sessão, lança sangria e suprimento, vê o extrato e o histórico
do dia e fecha com o saldo esperado à vista e a diferença apurada. No PDV, a busca aceita nome,
código e multiplicador de quantidade; a comanda mostra o total, o que já foi pago e o que falta,
admite pagamento dividido e exibe o troco. Uma venda simples em dinheiro fecha em até seis toques.
O comprovante usa a impressão do navegador e o compartilhamento do dispositivo quando disponível.
Na Venda, o Cliente ativo selecionado aparece na faixa de contexto do shell com seu saldo devedor;
a tela Fiado lista as dívidas e registra recebimentos parciais na SessaoCaixa aberta de quem recebe.
O comprovante da Venda indica o valor pendente e cada recebimento pode ser impresso ou compartilhado.
O mantenedor confirmou a instalação e abertura do PWA pelo ícone em desktop e tablet real (RNF08);
o R23 e o portão da Fase 1 estão concluídos. O R24 foi executado antes desse teste físico pela
exceção D53.
O faturamento abre no dia do balcão e permite consultar um período escolhido pelo administrador.
Um cliente HTTP só envia o token da sessão aberta nesta aba, troca-o pelo renovado quando a resposta
pertence à mesma sessão e traduz todo erro no mesmo objeto, lido do Problem Details. Respostas de
uma Conta anterior são descartadas depois de uma troca de sessão, inclusive entre abas.
Com token guardado e ainda válido, o aplicativo
abre sem rede no shell, com a identidade guardada, e pergunta ao servidor quem está operando assim
que a rede volta; a versão nova avisa e espera o operador mandar atualizar, porque recarregar no
meio de uma venda custaria a venda.

## Por onde começar a leitura

Atalhos para avaliar o código sem percorrer o repositório inteiro.

| O que olhar | Onde | Por que vale a leitura |
|---|---|---|
| Fronteira de módulo verificada por teste | [ModularityTests.java](src/test/java/br/com/caixasimples/ModularityTests.java) | A arquitetura falha o build quando é violada, em vez de depender de disciplina |
| PSP confinado ao adapter | [FronteiraPspTest.java](src/test/java/br/com/caixasimples/FronteiraPspTest.java) | Tipos da Efí e dependências de `pagamentos.internal` não chegam ao contrato nem aos casos de uso; o teste prova que a regra detecta um vazamento |
| Isolamento entre contas provado nos dois sentidos | [IsolamentoEntreContasTest.java](src/test/java/br/com/caixasimples/contas/IsolamentoEntreContasTest.java) | Segurança testada, não presumida: grava na conta A e prova que a conta B não enxerga |
| O mecanismo por trás desse isolamento | [MultiTenancyConfiguration.java](src/main/java/br/com/caixasimples/shared/internal/MultiTenancyConfiguration.java) | Filtro no Hibernate, não em cada consulta, e o que acontece quando não há tenant no contexto |
| Autorização explícita, sem anotação | [UsuarioContext.java](src/main/java/br/com/caixasimples/shared/UsuarioContext.java) | Quem chama vem do contexto, nunca de parâmetro, e cada caso de uso restrito pergunta na primeira linha se é o administrador ou o dono do caixa; o porquê de não haver `@PreAuthorize` nem tabela de rotas está escrito no lugar. [IdentidadeDoTokenFilter.java](src/main/java/br/com/caixasimples/contas/internal/IdentidadeDoTokenFilter.java) preenche os dois contextos e lê o usuário no banco a cada requisição, para inativar valer na hora |
| Gestão de usuários com o plano no caminho | [UsuarioService.java](src/main/java/br/com/caixasimples/contas/application/UsuarioService.java) | Criar usuário grava duas linhas numa transação, passa pela política de senha e pela unicidade global de e-mail, e é recusado fora do plano que admite mais de um usuário; inativar nunca deixa a conta sem administrador |
| Administração da Conta no PWA | [UsuarioController.java](src/main/java/br/com/caixasimples/contas/web/UsuarioController.java), [ConfiguracaoDaContaController.java](src/main/java/br/com/caixasimples/contas/web/ConfiguracaoDaContaController.java), [TelaDeUsuarios.tsx](frontend/src/telas/TelaDeUsuarios.tsx) e [TelaDeConfiguracao.tsx](frontend/src/telas/TelaDeConfiguracao.tsx) | A API usa a Conta do contexto e recusa o operador; o interruptor atualiza a identidade guardada e o menu sem recarga; o cadastro responde se a Conta já tem movimento antes de permitir desligar o controle |
| Um contrato de erro só, para o framework e para a aplicação | [TratamentoDeErrosHttp.java](src/main/java/br/com/caixasimples/shared/web/TratamentoDeErrosHttp.java) | Estende o tratador do Spring MVC em vez de substituí-lo, então corpo ilegível e recusa por perfil saem na mesma forma; cada status tem o porquê escrito no lugar, e o 404 de cada módulo fica no módulo, sem hierarquia de exceção. O molde do controller, com validação do pedido e a exceção própria traduzida no lugar, é [AutenticacaoController.java](src/main/java/br/com/caixasimples/contas/web/AutenticacaoController.java) |
| O primeiro login aplicando o catálogo uma vez | [ContaService.java](src/main/java/br/com/caixasimples/contas/application/ContaService.java) | Bloqueia a linha da Conta, marca o primeiro acesso e publica um evento síncrono para o cadastro copiar os itens na mesma transação; [PrimeiroAcessoDaContaListener.java](src/main/java/br/com/caixasimples/cadastro/internal/PrimeiroAcessoDaContaListener.java) reage sem criar dependência de Contas para o catálogo |
| Cadastro HTTP consumido pelas telas | [ProdutoController.java](src/main/java/br/com/caixasimples/cadastro/web/ProdutoController.java) e [ClienteController.java](src/main/java/br/com/caixasimples/cadastro/web/ClienteController.java) | A API conserva a Conta e o perfil fora do corpo, faz inativação lógica e devolve 404 para id de outra Conta; [TelaDeProdutos.tsx](frontend/src/telas/TelaDeProdutos.tsx) e [TelaDeClientes.tsx](frontend/src/telas/TelaDeClientes.tsx) exercitam o contrato no PWA |
| Caixa HTTP e conferência no PWA | [SessaoCaixaController.java](src/main/java/br/com/caixasimples/caixa/web/SessaoCaixaController.java) e [TelaDeCaixa.tsx](frontend/src/telas/TelaDeCaixa.tsx) | A sessão aberta vem do usuário autenticado, o histórico preserva o filtro por perfil, e só a consulta de uma sessão carrega os movimentos com a Venda de origem |
| Venda HTTP e PDV | [VendaController.java](src/main/java/br/com/caixasimples/vendas/web/VendaController.java) e [TelaDeVenda.tsx](frontend/src/telas/TelaDeVenda.tsx) | A comanda é recuperada após recarga, o pagamento dividido mostra o troco e a tela apresenta o comprovante não fiscal para imprimir ou compartilhar |
| Callback Pix e conciliação | [WebhookPixController.java](src/main/java/br/com/caixasimples/vendas/web/WebhookPixController.java) e [VendaPixService.java](src/main/java/br/com/caixasimples/vendas/application/VendaPixService.java) | O aviso autenticado localiza a parcela da Conta, reconsulta o PSP e deixa a raiz confirmar uma vez; a lista de conciliação mostra ao ADMIN dinheiro recebido após fechamento ou cancelamento |
| Faturamento HTTP e no PWA | [FaturamentoController.java](src/main/java/br/com/caixasimples/relatorios/web/FaturamentoController.java) e [TelaDeRelatorios.tsx](frontend/src/telas/TelaDeRelatorios.tsx) | A tela começa no dia do balcão e consulta um período escolhido; o caso de uso restringe ambas as consultas ao administrador e à Conta autenticada |
| Estoque HTTP e no PWA | [EstoqueController.java](src/main/java/br/com/caixasimples/estoque/web/EstoqueController.java) e [TelaDeEstoque.tsx](frontend/src/telas/TelaDeEstoque.tsx) | A API exige controle ligado e perfil ADMIN; a tela mostra saldo, mínimo e alerta e calcula a diferença da contagem antes do ajuste |
| O servidor entregando o aplicativo | [PwaConfiguration.java](src/main/java/br/com/caixasimples/shared/web/PwaConfiguration.java) | Fallback de página única escrito à mão, com o porquê de cada caso: rota do cliente recebe o shell, arquivo que não existe e rota da API que não existe recebem 404, e os cabeçalhos de cache são explícitos porque o cache heurístico do navegador seguraria uma página antiga. Fora de `/api` tudo é público, e a razão está em [SecurityConfiguration.java](src/main/java/br/com/caixasimples/contas/internal/SecurityConfiguration.java) |
| O cliente HTTP do aplicativo | [cliente.ts](frontend/src/api/cliente.ts) | Um lugar só envia o token, aceita a renovação da sessão que fez a chamada e traduz o Problem Details em um erro com status, detalhe e mensagem por campo; nenhuma tela precisa lembrar disso. A sessão no dispositivo, e o que acontece na abertura sem rede ou na troca entre abas, está em [SessaoProvider.tsx](frontend/src/sessao/SessaoProvider.tsx); a navegação por perfil, que esconde o que a API recusaria mas não decide autorização, em [menu.ts](frontend/src/shell/menu.ts) |
| Raiz de agregado sem framework | [SessaoCaixa.java](src/main/java/br/com/caixasimples/caixa/domain/SessaoCaixa.java) | Regra de negócio e invariantes isoladas de Spring e de JPA |
| Invariante viva, recalculada a cada operação | [Venda.java](src/main/java/br/com/caixasimples/vendas/domain/Venda.java) | O total nunca fica negativo nem diverge dos itens; a Venda CONCLUIDA é coberta por pagamentos confirmados e FIADO pendente. Recebimentos não excedem o fiado; o estado remontado do banco passa pela mesma conferência |
| Pergunta entre módulos sem expor o agregado | [VendaService.java](src/main/java/br/com/caixasimples/vendas/application/VendaService.java) | A venda copia o preço do cadastro por chamada à camada de aplicação dele, recebendo um record e nunca a raiz alheia; confere o caixa por uma interface que ela mesma declara; e, ao concluir ou cancelar, publica o evento em vez de chamar quem reage |
| Comprovante como dado, não como desenho | [Comprovante.java](src/main/java/br/com/caixasimples/vendas/application/Comprovante.java) | O servidor garante o dado certo, com as linhas arredondadas pelo domínio e as parcelas gravadas; quem desenha, imprime e compartilha é a tela, que precisa fazer isso também sem conexão. O porquê de não haver PDF nem HTML está escrito no lugar |
| Efeito colateral entre módulos por evento | [VendaConcluidaListener.java](src/main/java/br/com/caixasimples/caixa/internal/VendaConcluidaListener.java) | O caixa reage à venda concluída sem que a venda o conheça: só o dinheiro em espécie entra na gaveta, a reentrega do outbox não duplica, e o tenant é definido antes de a transação abrir, com o porquê escrito no lugar. [VendaCanceladaListener.java](src/main/java/br/com/caixasimples/caixa/internal/VendaCanceladaListener.java) é o oposto exato: o estorno espelha o que entrou, e quem sabe quanto foi é a sessão, não o evento |
| Um módulo que decide e outro que executa | [BaixaDeEstoqueListener.java](src/main/java/br/com/caixasimples/estoque/internal/BaixaDeEstoqueListener.java) | O estoque ouve o mesmo evento e decide, pela conta, se há o que baixar; o agregado é do cadastro, então a baixa é pedida pela API pública dele, e a fronteira continua verificada por compilação. [EstoqueService.java](src/main/java/br/com/caixasimples/estoque/application/EstoqueService.java) repete o desenho para o que uma pessoa aciona: ajuste, mínimo e alerta |
| Agregado que não carrega o próprio histórico | [ProdutoEntity.java](src/main/java/br/com/caixasimples/cadastro/internal/ProdutoEntity.java) | O saldo é coluna viva e o único método que a escreve exige o movimento junto; a coleção é preguiçosa de propósito, com o custo aceito escrito no lugar, porque o histórico de um produto cresce a cada venda e o produto é lido em toda venda |
| Dependência num sentido só | [CaixaParaVenda.java](src/main/java/br/com/caixasimples/vendas/CaixaParaVenda.java) | A pergunta da venda ao caixa é uma interface declarada em vendas e implementada no caixa, porque o caixa já depende de vendas para ouvir o evento e a verificação de fronteiras recusa ciclo |
| Schema gerado do framework, não adivinhado | [V8\_\_event_publication.sql](src/main/resources/db/migration/V8__event_publication.sql) | A tabela do outbox é mapeada por entidade do Modulith; a migration nasceu do DDL gerado dela, com a receita para repetir numa atualização e os dois ajustes tirados do schema oficial |
| Value object monetário | [Money.java](src/main/java/br/com/caixasimples/shared/Money.java) | Arredondamento explícito no ponto de uso, sem `double` e sem construtor que reescreve valor em silêncio |
| Uma decisão explicada onde ela mora | [FusoDeReferencia.java](src/main/java/br/com/caixasimples/shared/FusoDeReferencia.java) | Por que o fuso é constante única e não coluna, e o que precisa acontecer para reabrir a decisão |
| Strategy sem `switch` | [PaymentService.java](src/main/java/br/com/caixasimples/pagamentos/application/PaymentService.java) | Forma de pagamento nova é classe nova; duas estratégias para a mesma forma derrubam a aplicação na subida, em vez de uma sobrescrever a outra em silêncio |
| A regra do troco no domínio | [ResultadoPagamento.java](src/main/java/br/com/caixasimples/pagamentos/domain/ResultadoPagamento.java) | A conta mora numa fábrica nomeada de um tipo sem framework, e não no componente do Spring que a chama |
| Membro de agregado invisível de fora | [VendaEntity.java](src/main/java/br/com/caixasimples/vendas/internal/VendaEntity.java) | As entidades de item e pagamento têm visibilidade de pacote, então a proibição de repositório para elas não depende de disciplina |
| Um módulo que só lê, garantido por construção | [VendaParaRelatorio.java](src/main/java/br/com/caixasimples/relatorios/internal/VendaParaRelatorio.java) | A mesma tabela mapeada uma segunda vez, imutável e só com o que o relatório usa, para os relatórios lerem sem importar o pacote interno de vendas nem remontar o agregado; o repositório ao lado não tem método de escrita, é onde os filtros entram como parâmetro opcional da consulta, e explica por que o executor de Specification do Spring Data ficou de fora; o porquê de o dia ser o da conclusão e de a venda dividida se repartir por parcela está em [FaturamentoService.java](src/main/java/br/com/caixasimples/relatorios/application/FaturamentoService.java) |
| Uma consulta que junta três mapeamentos e arredonda como o domínio | [ItemVendaParaRelatorioRepository.java](src/main/java/br/com/caixasimples/relatorios/internal/ItemVendaParaRelatorioRepository.java) | O ranking dos mais vendidos numa JPQL só, com junções por id escritas na consulta, sem associação navegável, e o valor por item arredondado no banco pela mesma regra do comprovante; o porquê de o ranking ser por quantidade e de o fluxo de caixa ser o da gaveta está em [MaisVendidosService.java](src/main/java/br/com/caixasimples/relatorios/application/MaisVendidosService.java) e [FluxoDeCaixaService.java](src/main/java/br/com/caixasimples/relatorios/application/FluxoDeCaixaService.java) |
| Atributos variáveis em `JSONB` | [V2\_\_produto.sql](src/main/resources/db/migration/V2__produto.sql) | Índice GIN `jsonb_path_ops` e índice único parcial que só vale entre registros ativos |
| Vertical slice deliberado | [ClienteService.java](src/main/java/br/com/caixasimples/cadastro/internal/ClienteService.java) | Onde o projeto decide **não** aplicar DDD, porque não há invariante a proteger |

O javadoc do projeto explica **por que**, não o que o código já diz. As classes acima são um bom
exemplo disso.

## Arquitetura

**Monolito modular, não microsserviços.** Cada módulo é um Bounded Context do DDD e um pacote
direto abaixo de `br.com.caixasimples`. As fronteiras são verificadas em teste pelo Spring Modulith:
quebrar a fronteira quebra o build. A responsabilidade de cada módulo, e o que já está escrito,
está em [Estado atual](#estado-atual).

### Camadas dentro do módulo

```
<modulo>/
├── domain/        regra de negócio pura, sem Spring e sem Jakarta
├── application/   casos de uso, transação, orquestração
├── web/           controllers REST
└── internal/      JPA, adapters e configuração, invisível aos outros módulos
```

Nem todo módulo tem as quatro: uma camada nasce quando há o que colocar nela. Hoje `contas`,
`cadastro`, `caixa`, `vendas` e `relatorios` têm `web/` com os endpoints que suas telas consomem, e `shared` tem `web/` com o que é
transversal, o tratamento de erro e a entrega do aplicativo; `cadastro` acomoda `Cliente` inteiro
em `internal/`,
porque um slice sem invariante não precisa de `domain/`; e `relatorios` não tem `domain/` porque
não tem regra a proteger: ele lê colunas e soma.

A autorização por perfil mora na camada `application/`, como a primeira linha de cada caso de uso
restrito, e lê quem chama de um contexto em `shared`, preenchido pelo mesmo filtro que resolve o
tenant. Não é anotação nem tabela de rotas: quem lê o caso de uso vê quem pode chamá-lo, a regra
do próprio caixa precisa saber quem chama de qualquer modo, e a camada `web/` ainda não existe
para a maioria dos módulos. Nenhum caso de uso recebe perfil ou operador por parâmetro; um ouvinte
de evento, que roda sem usuário, só chama caso de uso que não pergunta.

Um módulo nunca importa de `internal/` de outro. Efeito colateral entre módulos é Domain Event;
consulta é chamada direta à API pública do pacote. A regra que resume as duas: *eventos anunciam
fatos, chamadas diretas fazem perguntas.* O Spring Modulith só expõe o pacote-base de cada módulo,
então a camada `application/` é exposta nomeadamente onde outro módulo precisa perguntar algo, e
`domain/` e `internal/` seguem ocultos: `vendas` copia o preço do produto, e pede o nome dele para
o comprovante, recebendo um record da camada de aplicação de `cadastro`, nunca a raiz de agregado
alheia. A exceção é `pagamentos`, que
expõe também o `domain/`: ele não tem agregado, e o que mora lá é a interface do Strategy e os dois
records imutáveis que são o contrato de pagar, o pedido e o resultado, sem os quais ninguém
consegue chamar o serviço de pagamento.

A verificação de fronteiras também recusa **ciclo** entre módulos, e ouvir um evento é depender de
quem o publica. Como o caixa ouve a venda concluída, a venda não pode chamar o caixa: a única
pergunta que ela faz, se a sessão está aberta, passa por uma interface declarada em `vendas` e
implementada em `caixa/internal`. A dependência entre os dois fica num sentido só, e o teste de
arquitetura garante isso por compilação, não por revisão.

Os listeners de evento moram em `internal/`, por serem adapters de entrada. Os da venda rodam
depois do commit e não usam a anotação composta que o Modulith oferece: ela abriria a transação
antes de o tenant estar no contexto, e o Hibernate resolve o tenant na abertura da sessão. O molde
define a conta a partir do evento e só então abre a transação. O primeiro acesso da Conta tem um
ouvinte síncrono: marca e catálogo são gravados juntos, antes de o login responder.

O mesmo evento tem dois ouvintes, e o segundo mostra o outro lado da regra de ciclo. O estoque
reage à venda concluída, mas o agregado que ele move, o produto com seu saldo, é do cadastro, e o
cadastro não pode ouvir o evento sem fechar um ciclo com a venda, que já lhe pergunta o preço. A
saída foi separar quem decide de quem executa: o módulo de estoque guarda a política, se a conta
ligou o controle e o que fazer com cada item, e pede a baixa ao cadastro pela camada de aplicação
dele. Ninguém depende de `estoque`, e é isso que permite a ele depender de três módulos.

### Padrões aplicados

Nenhum padrão entra por simetria, e nenhum entra antes do problema que o justifica. O que está em
uso hoje:

- **Repository apenas por raiz de agregado.** Não existe `MovimentoCaixaRepository`,
  `ItemVendaRepository` nem `PagamentoRepository`: membro de agregado entra e sai pela raiz.
- **Strategy** por forma de pagamento, resolvido por mapa e sem `switch` em código de negócio:
  dinheiro, Pix integrado, cartão e FIADO têm uma classe cada. A parcela Pix nasce PENDENTE.
- **Adapter** de Pix atrás de `PixGateway`: Efí usa OAuth e certificado por Conta, cria cobrança
  com `txid` derivado do UUID da parcela e compara os dados do PSP antes de mostrar o copia e cola
  ou confirmar um Pix recebido. Ao cancelar, pede a remoção da cobrança ainda pendente e reconsulta
  antes de recusar a parcela; o PWA não reapresenta QR de cobrança removida. Pix pago continua
  confirmado e requer devolução fora do sistema.
  O webhook só fornece a correlação para a reconsulta.
- **Value object** para dinheiro, com o arredondamento visível em quem o chama.
- **Domain Events com outbox** para a venda concluída, e a cancelada, avisarem o caixa e o
  estoque sem acoplar os três: a publicação é gravada na mesma transação que conclui ou cancela
  a venda, cada listener roda depois do commit, em outra thread, e uma falha deixa a publicação
  incompleta em vez de perder o efeito. Cada evento carrega o fato inteiro, itens e parcelas, e
  quem decide o que fazer com ele é quem ouve: o caixa lê as parcelas, o estoque lê os itens. O
  cancelamento é o espelho da conclusão, com dois ouvintes que desfazem o que os dois primeiros
  fizeram, lançando o movimento oposto em vez de apagar o original.
- **Evento síncrono no primeiro acesso da Conta** para o cadastro copiar o catálogo sugerido na
  mesma transação que grava a marca. Uma falha na cópia desfaz a marca, e dois logins simultâneos
  são serializados pela linha da Conta.
- **Dependency inversion entre módulos** onde uma pergunta e um evento cruzariam em sentidos
  opostos: a interface mora em quem pergunta, a implementação em quem responde.
- **Vertical slice** onde não há invariante a proteger, em vez de agregado por simetria.
- **Modelo de leitura próprio no módulo que só lê.** Os relatórios não remontam agregado nem
  importam a entidade de outro módulo: mapeiam a mesma tabela uma segunda vez, como entidade
  imutável com só as colunas que a consulta usa, e o repositório herda do marcador do Spring Data
  em vez do completo, sem `save` nem `delete`. O só-leitura fica garantido por construção, em três
  pontos, e não por revisão. A soma é feita no banco, em JPQL, que o filtro de conta alcança; o
  ranking junta três desses mapeamentos por id, na própria consulta, sem associação navegável, e
  arredonda cada item no banco do mesmo jeito que o domínio arredonda no comprovante. Os filtros
  combináveis entram nessas mesmas consultas como condição que some quando o parâmetro é nulo,
  e o filtro por forma de pagamento é uma consulta própria, que soma parcelas em vez de vendas.
- **Fitness function de arquitetura**, que transforma a regra de fronteira em teste.

### Padrões decididos, ainda não escritos

**Specification** esteve nesta lista, para os filtros combináveis dos relatórios,
e saiu quando chegou a hora: o executor que o Spring Data oferece traz `update` e `delete` para
dentro de um repositório que não pode escrever, e não agrega, enquanto as consultas dos relatórios
são somas; os filtros entraram como parâmetro opcional nas consultas que já existiam.

## Decisões estruturais

- **Multi-tenancy por coluna.** Toda entidade de negócio tem `contaId` anotado com `@TenantId`, e o
  Hibernate aplica o filtro sozinho. Três exceções deliberadas: `ModeloProduto` (dado de referência
  da plataforma), `Conta` (o `id` dela *é* o tenant) e `Credencial` (consultada no login, antes de
  existir tenant). A tabela do outbox de eventos é do framework, não do negócio, e por isso fica
  fora da lista: a conta a que cada evento se refere viaja dentro dele.
- **Quem chama também é contexto, nunca parâmetro.** Ao lado do tenant, a requisição carrega o
  usuário e o perfil dele, lidos do banco a cada requisição e não do token, para que inativar
  alguém valha na hora. A verificação é uma chamada explícita na primeira linha do caso de uso
  restrito: o administrador faz tudo em qualquer caixa da conta; o operador só vende, cadastra
  cliente e opera o próprio caixa. O caixa e a venda são de quem os abriu.
- **Chave primária é UUID gerado na aplicação**, nunca auto-incremento. É o que permite criar um
  registro offline com identidade definitiva e sincronizar depois sem renumerar nada, tornando o
  reenvio de uma operação naturalmente idempotente.
- **O schema pertence ao Flyway.** O Hibernate roda com `ddl-auto: validate` e apenas confere se
  bate. Migration já publicada é imutável: correção é sempre versão nova, como a `V6` é para a `V5`,
  e como a `V7` acrescenta a `movimento_caixa` a chave estrangeira que a `V5` não podia criar,
  porque a tabela de venda ainda não existia. Vale até para tabela que não é do projeto: a do
  outbox do Modulith, na `V8`, foi gerada a partir da entidade do framework, e a suíte prova que o
  gerado bate, porque o contexto não subiria se não batesse.
- **Repositório só para raiz de agregado.** Membro de agregado (item de venda, pagamento,
  recebimento, movimento de caixa ou de estoque) entra e sai pela raiz, o que impede alterar um item sem recalcular o total
  que a raiz garante.
- **Soft delete**, nunca exclusão física, para preservar o histórico de vendas antigas.
- **Dinheiro é um value object `Money`**, `numeric(12,2)` no banco, com arredondamento a duas casas
  visível no ponto de uso. Nada de `double`.
- **O dia é o do balcão.** Timestamps gravados em UTC; a fronteira do dia vem de uma constante única
  da aplicação. Sem isso, todo caixa aberto depois das 21h cairia no dia seguinte do relatório, e
  nada denunciaria o erro.
- **DDD onde existe invariante para proteger.** `SessaoCaixa`, `Produto` e `Venda` têm domínio
  rico. `Cliente`, que não tem invariante, é um vertical slice em um arquivo só. A régua: se
  acrescentar um campo exigir tocar em mais de três ou quatro arquivos, é cerimônia demais.
- **Regra de negócio entra com o caso de uso, não com a tabela.** A montagem da comanda trouxe as
  regras do total; a conclusão trouxe as dos pagamentos; o cancelamento trouxe o estado final;
  o fiado trouxe o vínculo de Cliente e recebimentos pela raiz da Venda. Testes que precisam de um
  estado específico usam o mesmo método que a entidade usa para remontar o agregado do banco.
- **Concluir é um passo, não um efeito.** Registrar a parcela que fecha a conta não conclui a
  venda; quem finaliza chama a operação que diz isso. Um método de registrar pagamento que às vezes
  mudasse o status seria comportamento escondido no nome, e o evento de venda concluída nasce de
  um ponto só. Concluir exige o caixa em que a venda nasceu ainda aberto, porque é nele que o
  dinheiro entra; registrar parcela não exige, porque parcela não mexe na gaveta.
- **No caixa entra só o dinheiro.** O esperado da sessão é o que deveria haver na gaveta, e é
  contra ele que o operador confere o que contou. Pix e cartão nunca estiveram lá: uma venda paga
  sem dinheiro não gera movimento nenhum, e uma venda dividida lança só a parte em espécie.
- **Cancelar desfaz pelo oposto, e só com o caixa aberto.** Uma venda concluída cancelada
  devolve ao estoque o que levou e à gaveta o que trouxe, com um movimento novo de cada lado, e
  nunca apagando o original: o histórico continua contando que entrou e saiu. O estorno no caixa
  espelha o valor da venda, e é a sessão que sabe quanto foi, não quem cancela. Uma venda
  concluída só cancela com a sessão em que nasceu ainda aberta, porque a gaveta de uma sessão
  fechada já foi conferida; a comanda aberta sem cobrança Pix integrada cancela sem perguntar,
  porque nunca tocou a gaveta, e é a saída para a comanda que ficou presa quando o caixa fechou
  antes. Pix manual histórico e cartão confirmados ficam como estão: não há provedor a quem pedir
  estorno, e a devolução ao cliente acontece no balcão. Uma Venda com Pix integrado pendente só
  cancela após o PSP comprovar que a cobrança foi removida; falha ou resposta incerta mantém a
  Venda e permite repetir. Se o Pix já foi pago, a parcela segue CONFIRMADO e o PWA avisa que a
  devolução é manual e ainda exige conciliação. O estorno pode deixar o esperado negativo se houve
  sangria no meio, pelo mesmo motivo do saldo de estoque negativo: o cancelamento já aconteceu,
  e recusar só deixaria o caixa sem refletir o fato.
- **O preço nunca vem de quem chama.** Lançar um item recebe o id do produto, e o caso de uso
  consulta o preço vigente no cadastro na hora de gravar. Um preço vindo do pedido seria uma porta
  para vender por qualquer valor; a cópia feita ali é o que impede uma venda passada de mudar quando
  o produto é reajustado.
- **O saldo de estoque pode ficar negativo.** A venda que levou mais do que o saldo registrava já
  aconteceu no balcão; recusar a baixa não a desfaria, só deixaria o estoque mentindo por omissão,
  com o evento preso no outbox. O saldo negativo é o fato a corrigir, por um ajuste de contagem, e
  é o que o alerta de estoque baixo expõe. O controle nasce desligado por conta, e ligar não
  conta o que já está na prateleira: a contagem inicial é um ajuste manual.
- **O limiar de estoque baixo é por produto, e nasce em zero.** Baixo depende do item: dois quilos
  de queijo e duas garrafas de água não são o mesmo baixo. Sem número configurado, o alerta avisa
  quando o item acabou, zero ou negativo; quem informa um mínimo maior é avisado antes. Nunca há
  produto que não alerta, e por isso a coluna não é anulável. O alerta é uma consulta, a lista dos
  produtos ativos no mínimo ou abaixo, e não um evento: ninguém o ouviria hoje.

## Segurança e isolamento

O isolamento entre contas é tratado como requisito de segurança, e o desenho reflete isso nos
pontos abaixo. A autorização dentro da conta, por perfil, segue a mesma postura: verificada no caso
de uso, provada por teste negativo, e nunca dependente de um valor que o cliente informe.

- **O `contaId` nunca vem do corpo da requisição.** Ele sai do claim do token autenticado, então
  não existe parâmetro que um cliente possa forjar para alcançar dado de outra conta.
- **O filtro é do Hibernate, não de cada consulta.** Uma consulta nova nasce filtrada por
  construção; esquecer o `where` não é uma falha possível. Em contrapartida, o filtro não alcança
  SQL nativo, e por isso query nativa em código de negócio é proibida no projeto.
- **Todo dado persistido tem teste de isolamento**, no molde de gravar na conta A e provar que a
  conta B recebe vazio. Usuário, credencial, produto, cliente, sessão de caixa e venda têm o seu, e
  os membros de agregado (movimento de caixa, item e pagamento da venda, movimento de estoque) têm
  um teste próprio, que prova que a coluna de conta deles vem do contexto e não da raiz por
  junção. O teste falha se a anotação de tenant for removida. Os casos de uso do estoque têm o
  seu nas duas direções: a lista de estoque baixo de uma conta não traz o produto de outra, e o
  ajuste de uma conta não alcança o produto de outra. O comprovante tem a mesma prova nas duas
  pontas: a venda de uma conta é inexistente para a outra, e a consulta em lote dos nomes dos
  produtos, que ele faz ao cadastro, também. Os três relatórios têm a prova que o plano pediu:
  duas contas com dados equivalentes no mesmo dia recebem cada uma só o seu faturamento, o seu
  ranking e o seu fluxo de caixa, e uma terceira, sem nada, recebe zero ou vazio; as consultas
  agregadas em JPQL, inclusive a que junta três tabelas, passam pelo mesmo filtro que as outras.
  Os filtros por forma de pagamento e por operador têm a mesma prova: a conta que pede o
  faturamento ou o ranking pelo operador de outra recebe zero ou vazio, porque o id existe, mas
  não nela. O cadastro HTTP também prova as listas de produtos e clientes vazias na outra Conta,
  inclusive a lista de clientes inativos, e o 404 ao tentar editar pelo id alheio. O primeiro
  acesso aplica a cada Conta uma cópia própria do catálogo sugerido.
  O caixa HTTP também prova que a Conta B não encontra a sessão da Conta A, recebe histórico vazio
  e não deduz um caixa aberto alheio.
  O callback Pix autenticado pela configuração da Conta B não encontra o `txid` persistido na
  Conta A; a lista de conciliação também devolve somente Vendas da Conta autenticada. O teste
  HTTP de cancelamento recusa a tentativa da Conta B antes de chamar o PSP da Conta A.
- **Os listeners da venda agem na conta do evento, não na de quem publicou.** Eles rodam em outra
  thread, sem o tenant da requisição, e uma reentrega pode partir do outbox horas depois; a conta
  vai dentro do evento, lida do contexto autenticado no ato da publicação, e há teste, para os
  ouvintes de conclusão e cancelamento, que publica como uma conta e prova que o efeito cai na
  conta do evento e que a outra não o vê. `FiadoServiceTest` prova o recebimento no caixa da
  Conta e o 404 ao tentar receber a Venda pela Conta alheia. A tabela do outbox não tem coluna de
  conta, porque é do framework e nenhum
  código de negócio a lê.
- **A pergunta sobre a conta não aceita conta.** A tabela de contas é a única sem filtro
  automático, porque o id dela é o tenant; em troca, o serviço que responde por ela lê a conta do
  contexto e nada mais, sem assinatura por onde perguntar sobre outra.
- **O perfil não vem do token nem de parâmetro.** O filtro que resolve o token lê o usuário no
  banco a cada requisição, pela chave, já sob o tenant: inativo recebe 401 antes de qualquer caso
  de uso, e o perfil que entra no contexto, e no token renovado, é o do banco. Cada caso de uso
  restrito tem teste de autorização negativa: o operador não cadastra produto, não mexe no
  estoque, não lê relatório, nem o filtrado por ele mesmo, e não gere usuários; não lança, fecha
  nem consulta o caixa do colega, não pede o histórico do colega nem o da conta inteira, não
  inicia venda no caixa do colega e não toca a venda dele por nenhum caso de uso. O administrador
  faz tudo isso, e há teste de que fecha o caixa do operador e cancela a venda dele. A gestão de
  usuários tem a prova de isolamento nos dois sentidos, e a prova de que o token que um operador
  já tinha deixa de entrar assim que ele é inativado.

A autenticação usa JWT emitido e validado pelo próprio Spring Security, sem biblioteca de JWT de
terceiro, com API stateless e senha em BCrypt verificada contra bases de senhas vazadas. A política
de senha exige comprimento e nenhuma regra de composição, porque exigir símbolo e maiúscula empurra
o usuário para uma senha pior, anotada num papel no balcão.

A validade do token conta a partir do último contato com o servidor e não do login: toda resposta
autenticada devolve um token renovado. Na prática, é a janela de resistência que a operação offline
exige. Não há revogação de token, e sim de usuário: inativar alguém derruba o token dele na
requisição seguinte. No aplicativo, o token e a identidade de quem entrou ficam guardados no
dispositivo, e é isso que permite abrir o aplicativo sem rede. Cada login inicia uma sessão nova
no navegador; token e identidade ficam vinculados a ela, cada aba só usa a sessão com que abriu,
e uma troca em outra aba remove a identidade anterior da tela. O custo aceito, e escrito, é que
quem pega o tablet destravado entra até o token expirar. O cliente nunca envia conta nem perfil em
requisição nenhuma, e a navegação por perfil apenas esconde o que o servidor recusaria: a
autorização continua sendo do caso de uso.

Usuário novo nasce pela gestão de usuários da conta, feita pelo administrador: um `Usuario`, que
carrega o perfil e o tenant, e uma `Credencial`, que carrega e-mail e senha, gravados na mesma
transação. A senha inicial passa pela mesma política de qualquer senha, inclusive a verificação de
vazamento, que recusa se não puder verificar; criar usuário é raro, quem cria é o dono e ele está
conectado. Mais de um usuário por conta é recurso do plano mais alto, e a conta nunca fica sem um
administrador ativo.

Nenhum segredo mora no repositório. Chave de assinatura e credenciais vêm de variável de ambiente, e
a aplicação recusa subir sem a chave, em vez de cair num valor padrão que seria idêntico em toda
instalação.

## Modelo de dados

Toda tabela de negócio carrega `conta_id`. Enums são `varchar` com `CHECK`, e não o tipo `ENUM` do
Postgres, porque acrescentar valor a um `ENUM` exige `ALTER TYPE` fora de transação. Valores
monetários são `numeric(12,2)` e timestamps são `timestamptz` gravados em UTC.

### Agregados

| Agregado | Raiz | Membros | O que a raiz garante | Estado |
|---|---|---|---|---|
| **Venda** | `Venda` | `ItemVenda`, `Pagamento`, `Recebimento` | `valor_total` reflete a soma dos itens menos o desconto; numa Venda concluída, pagamentos confirmados e FIADO pendente cobrem o total, e os recebimentos não excedem o FIADO | Implementado: regras conferidas também ao remontar o agregado do banco. CANCELADA é estado final, com itens, parcelas e recebimentos preservados |
| **Caixa** | `SessaoCaixa` | `MovimentoCaixa` | `valor_fechamento_esperado` reflete o valor de abertura mais a soma assinada dos movimentos | Implementado |
| **Produto** | `Produto` | `MovimentoEstoque` | `estoque_atual` reflete a soma dos movimentos, atualizado na mesma transação | Implementado para os três tipos, a saída por venda, a entrada do cancelamento e o ajuste manual: o único método que escreve o saldo exige o movimento junto, o ajuste sem motivo não passa pela raiz, e não se devolve o que não saiu |
| Entidade única | `Conta`, `Usuario`, `Cliente` | nenhum | são agregados de uma entidade só; a marca do primeiro acesso da Conta só avança uma vez | Implementado |

Referência que cruza agregado é sempre por ID, nunca um `@ManyToOne` navegável. É o que impede
editar um agregado através de outro.

### Detalhes de modelagem que valem nota

- **Catálogo inicial marcado na Conta.** O primeiro login de administrador bloqueia a linha da
  Conta, marca a aplicação do catálogo e publica o evento que copia os itens, tudo na mesma
  transação. Um segundo login não duplica os produtos, mesmo se o catálogo estiver vazio por não
  haver sugestões para aquele tipo de negócio.
- **Índice único parcial** no código do produto, válido apenas entre os registros ativos, para que
  um item inativado não bloqueie a reutilização do código. A unicidade é por conta, e ignora
  maiúsculas, porque o operador digita rápido no balcão e não pode perder a venda por causa disso.
- **Uma sessão de caixa aberta por operador**, garantida por índice único parcial sobre as sessões
  em aberto. Dois atendentes podem ter caixas simultâneos no mesmo negócio; o mesmo operador com
  dois, não. O índice é a rede embaixo da checagem do caso de uso, para o caso de duas requisições
  passarem juntas.
- **`MovimentoCaixa` tem um campo `tipo`** único para venda, estorno, sangria e suprimento, o que
  mantém o fechamento de caixa como uma soma simples em vez de juntar quatro tabelas. O valor
  gravado é sempre positivo, e quem carrega o sinal é o tipo. O movimento de venda nasce do
  evento de venda concluída, vale só a parte paga em dinheiro, e a mesma venda não entra duas
  vezes na mesma sessão: a raiz recusa a duplicata, e o listener reconhece a reentrega antes de
  chegar nela. O estorno nasce do evento de venda cancelada, aponta para a mesma venda, vale
  exatamente o que a venda trouxe e sai uma vez só; um índice único parcial por sessão, venda e
  tipo é a rede embaixo dos dois, para duas entregas simultâneas do mesmo evento.
- **`estoque_atual` é consolidado na raiz**, e não somado do histórico a cada leitura, para o alerta
  de estoque baixo não pagar esse preço. Levado às últimas consequências: a raiz não carrega o
  histórico, ao contrário da sessão de caixa, cujo extrato é um expediente. O histórico de um
  produto cresce a cada venda e o produto é lido em toda venda, então a coleção é preguiçosa, o
  domínio conhece só o saldo, e cada baixa devolve o movimento que a explica para que os dois sejam
  gravados juntos. A pergunta de reentrega, se a mesma venda já baixou este produto, vai ao
  repositório por consulta derivada, e um índice único parcial é a rede embaixo.
- **`MovimentoEstoque` segue o mesmo molde do movimento de caixa**: entrada, saída e ajuste numa
  tabela só, quantidade sempre positiva na entrada e na saída, com o sinal no tipo. O ajuste é a
  exceção: serve aos dois sentidos, então o sinal vai na quantidade, e um `-2` no histórico se lê
  sozinho como uma perda de dois. O schema deixou essa convenção em aberto até o ajuste existir,
  para a decisão ser tomada com o caso de uso na mão em vez de presa numa migration imutável, e a
  migration seguinte reemitiu os comentários das colunas com a convenção decidida.
- **O estoque mínimo é caso de uso próprio, não campo do formulário de cadastro.** O limiar é
  política de estoque, não descrição do item, e só faz sentido com o controle ligado; num salão o
  formulário carregaria um campo morto. Por isso passa pelo módulo de estoque, que decide se a
  conta participa, e o cadastro não muda de forma.
- **`preco_unitario` do item é cópia** do preço do produto no momento da venda, nunca leitura viva:
  reajustar o produto depois não pode alterar o valor de uma venda passada. Há teste que reajusta o
  produto e prova que a venda gravada não muda.
- **O arredondamento é por item, e o método que multiplica diz isso no nome.** Setecentos e
  cinquenta gramas a 39,90 dão 29,925, que vira 29,93 na linha; o total é a soma de linhas já
  arredondadas, para que o comprovante feche com o total impresso.
- **Desconto nunca passa do valor.** Nem o do item sobre o bruto do item, nem o da venda sobre a soma
  dos itens; a raiz recusa a operação, em vez de gravar um subtotal negativo ou limitar em silêncio.
  Brinde é preço zero, não desconto acima do valor.
- **Item lançado não se edita.** Quantidade e desconto entram junto com o item; corrigir é remover
  e lançar de novo, e o mesmo produto pode aparecer em duas linhas da mesma venda.
- **`Pagamento` é entidade própria**, e não colunas na venda, porque uma venda pode ser dividida
  entre formas: metade em dinheiro e metade no cartão são dois registros, cada um com o seu valor e
  o seu estado.
- **Parcela nunca passa do que falta pagar, e o total nunca fica abaixo do já pago.** Lançar uma
  parcela maior que o saldo é recusado como erro de digitação; remover item ou aplicar desconto
  que deixasse o total menor que as parcelas lançadas também. Uma parcela pendente, à espera de um
  provedor, reserva o lugar dela na conta; uma recusada não ocupa lugar. Parcela lançada não se
  desfaz: o valor errado se corrige cancelando a venda.
- **A Venda só conclui quando os pagamentos confirmados e o FIADO pendente cobrem exatamente o total**, e nunca sem
  item. O troco é calculado pelo módulo de pagamentos, devolvido a quem chamou para a tela mostrar
  no ato, e gravado na parcela, para o comprovante sair igual numa reimpressão. Zero fora de
  dinheiro, e o banco recusa troco em Pix ou cartão.
- **A venda guarda dois instantes:** quando a comanda abriu e quando os pagamentos fecharam a
  conta. O segundo é a data do comprovante, porque numa comanda os dois podem estar horas
  distantes; nasce na conclusão, e o cancelamento não o apaga. É também o que delimita o dia do
  faturamento: a venda conta no dia da conclusão, inclusive com FIADO pendente, e uma comanda aberta às 23h50 e paga
  às 00h10 é do dia seguinte. A sessão de caixa, por sua vez, é do dia em que abriu, porque um
  expediente pode atravessar a meia-noite e continua sendo um só.
- **As tabelas de venda, item de venda, produto, pagamento e movimento de caixa têm um segundo
  mapeamento, somente leitura**, no módulo de relatórios: imutável, com as colunas que o relatório usa e
  nenhuma outra, sem construtor que a instancie. O esquema é o contrato entre os dois
  mapeamentos, versionado nas migrations, e o Hibernate valida os dois na subida: renomear uma
  coluna no módulo dono derruba a aplicação no deploy, que é o modo certo de falhar, e não com
  relatório em branco. Os mapeamentos de produto e de pagamento nem têm repositório: existem para
  ser alvo de junção; o de produto traz o nome atual para o ranking mesmo depois de o produto ser
  inativado, e o de pagamento é o que permite somar por forma o valor de cada parcela, e não o
  total da venda que a usou.
- **O fluxo de caixa é o da gaveta, e o dia é o do movimento.** O caixa só registra dinheiro em
  espécie, então entradas são vendas em dinheiro e reforços de troco, saídas são retiradas e
  estornos de venda cancelada, e o saldo é a diferença; o troco inicial da sessão não é
  movimento e fica de fora. O período é delimitado pelo instante em que cada movimento foi
  lançado, e não pelo dia da sessão: um expediente que vira a meia-noite reparte os movimentos
  entre os dois dias, enquanto o histórico do caixa o mantém inteiro no dia em que abriu. As duas
  perguntas são diferentes, e as duas respostas convivem.
- **O comprovante é dado, não desenho.** O caso de uso devolve um record com as linhas já
  calculadas, o nome que o produto tem hoje, os descontos, as parcelas confirmadas e o troco;
  quem desenha, imprime e compartilha é a tela, que precisa fazer isso também sem conexão. Só
  venda concluída tem comprovante, e ele não se parece com documento fiscal.
- **Estado divergente não vira agregado.** Ao remontar uma venda do banco, a raiz confere as duas
  regras e recusa a linha cujo total não bate com os itens, ou concluída sem os pagamentos que a
  fecham. Uma linha gravada por fora do código deixa de ser legível pelo domínio até ser corrigida,
  em vez de circular com um total que ninguém confere.
- **A venda nasce aberta.** É o estado da comanda em montagem e também o da venda que espera a
  confirmação de um pagamento que chega depois, como uma cobrança de Pix gerada por provedor. Os
  dois são o mesmo estado porque em ambos a venda existe e ainda não está paga.
- **Toda coluna numérica tem restrição de sinal no banco.** Valor e preço nunca negativos, quantidade
  sempre positiva, desconto ausente gravado como zero e não como nulo. O domínio repete as mesmas
  guardas; a restrição no banco existe para um script de correção não gravar o que o código recusa.

## Qualidade e testes

A suíte roda contra um **PostgreSQL real** provisionado por Testcontainers, nunca contra banco em
memória: o projeto usa `JSONB`, índice GIN, índice único parcial e `timestamptz`, e um banco
substituto validaria um schema que não é o de produção.

Quatro tipos de teste sustentam o projeto:

| Tipo | O que garante |
|---|---|
| **Fitness function de arquitetura** | As fronteiras entre módulos continuam intactas, e a violação quebra o build |
| **Teste de isolamento** | Nenhum dado novo vaza entre contas, verificado nos dois sentidos |
| **Teste de invariante** | Toda raiz de agregado recusa a operação que quebraria a regra que ela protege |
| **Teste de caso de uso** | O comportamento observável de cada operação, incluindo os casos de borda decididos explicitamente |

Nenhuma etapa fecha com teste vermelho. Regra do projeto: dado persistido novo exige teste de
isolamento, raiz de agregado tocada exige teste que tenta violar a invariante e espera falha, e
caso de uso restrito por perfil exige teste de autorização negativa, que chama como operador e
espera a recusa. Todo teste que chama um caso de uso executa como alguém: a fixture de conta
define o tenant e o usuário juntos, como o filtro do token faz numa requisição de verdade, e um
caso de uso chamado sem usuário falha fechado. A exceção é a entrada do webhook Pix: ela não
representa uma pessoa, resolve a Conta pela configuração autenticada da URL e só pode confirmar
uma parcela já persistida depois de reconsultar o PSP.

Os eventos de domínio são testados com o outbox de verdade: o teste publica, espera cada listener
terminar em outra thread e confere o movimento no caixa e a baixa no estoque, e depois o estorno
e a entrada que os desfazem; a reentrega que não duplica em nenhum dos quatro; a conta desligada
que não baixa nem devolve nada; a conta que ligou o controle depois da venda e não devolve o que
nunca saiu; e a publicação concluída no registro, remontada do JSON igual ao evento original.
Como há dois ouvintes por evento, quem conta publicações concluídas filtra pelo ouvinte.

## Stack

| Camada | Escolha |
|---|---|
| Linguagem | Java 21, com virtual threads habilitadas, já que a carga é I/O-bound |
| Framework | Spring Boot 4.1.1 |
| Modularidade | Spring Modulith 2.1.1 |
| Persistência | Spring Data JPA e Hibernate ORM |
| Banco | PostgreSQL 17, com `JSONB` e índice GIN `jsonb_path_ops` |
| Migrations | Flyway |
| Segurança | Spring Security e OAuth2 Resource Server, sem biblioteca de JWT de terceiro |
| Build | Maven, via wrapper versionado |
| Testes | JUnit 5, AssertJ e Testcontainers |
| Frontend | PWA em React 19, Vite 8 e TypeScript, com React Router, service worker gerado por Workbox e Vitest; empacotado no jar pelo Maven, com o Node fixado no `pom.xml` |
| Infraestrutura | validação local sem clientes (R25); hospedagem pública a escolher antes do deploy (R38) |

A escolha de versão não é acidental. Spring Boot 3.x perde suporte OSS em junho de 2026, então um
projeto novo não deveria nascer nele; o Spring Modulith 2.1.x é a linha compatível com o Boot 4.1.

## Estrutura do repositório

```
src
├── main
│   ├── java/br/com/caixasimples
│   │   ├── cadastro/       domain, application, web, internal
│   │   ├── caixa/          domain, application, web, internal
│   │   ├── contas/         application, web, internal
│   │   ├── pagamentos/     domain, application, internal
│   │   ├── vendas/         domain, application, web, internal
│   │   ├── shared/         Money, ContaId, TenantContext, UsuarioContext, Perfil, FusoDeReferencia; web com o tratamento de erro e a entrega do aplicativo
│   │   ├── estoque/        application, web, internal
│   │   └── relatorios/     application, web, internal
│   └── resources
│       └── db/migration/   V1 a V16, imutáveis depois de publicadas
├── test/java/br/com/caixasimples
│   ├── ModularityTests     fitness function das fronteiras
│   ├── TesteDeIntegracao   base com Testcontainers, herdada pelos testes de banco
│   └── ...                 testes por módulo, incluindo isolamento entre contas
frontend
├── src
│   ├── api/                o cliente HTTP e as chamadas de cadastro, caixa, vendas, faturamento e estoque
│   ├── sessao/             token e identidade no dispositivo, provedor de sessão
│   ├── shell/              cabeçalho, navegação por perfil, guardas de rota
│   └── telas/              login, produto, cliente, caixa, Venda, faturamento, usuários, configuração e estoque
├── public/                 ícones e manifest
└── vite.config.ts          build, service worker e o proxy de desenvolvimento para a API
```

Todo módulo declarou a fronteira antes de ter código, e o `ModularityTests` a verificava desde
então. Foi assim que a primeira classe de `pagamentos` nasceu dentro de um limite que já existia,
em vez de criar o limite depois do código; `estoque` nasceu do mesmo jeito, com o ouvinte da venda
concluída entrando num pacote cuja fronteira já era testada; e `relatorios`, o último, nasceu com
o faturamento dentro de uma fronteira que já dizia que ele só lê. `estoque` não tem `domain/` nem
tabela própria, e não é omissão: ele é política, a conta participa ou não, e o agregado que ele
move é do cadastro. Os ouvintes em `internal/` e os casos de uso em `application/` decidem e
pedem; quem executa é o dono do agregado. `relatorios` também não tem `domain/` nem tabela: ele
lê as tabelas dos outros por mapeamentos próprios, toma dos módulos donos só os enums do
pacote-base, e ninguém depende dele. O `frontend/` fica na raiz, e não dentro de `src/`, para
quem lê o repositório ver que existe um aplicativo sem entrar na árvore Java; o Maven o compila
e testa na fase de empacotamento e copia o resultado para dentro do jar, então a suíte Java roda
sem Node instalado e o artefato de produção é um só.

## Autor

**Elias Costa**

- LinkedIn: https://www.linkedin.com/in/elias-rodrigues-288450254/
- E-mail: ecrprofessional@gmail.com

Aberto a oportunidades como desenvolvedor back-end Java.

## Licença

**Todos os direitos reservados.** Texto completo em [LICENSE](LICENSE).

O código é publicado exclusivamente para leitura e avaliação técnica. Este projeto não é software
livre e não recebe licença de uso: não há permissão para usar, copiar, modificar, distribuir ou
criar obra derivada, no todo ou em parte, sem autorização escrita do autor.
