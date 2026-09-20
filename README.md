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
primeiro dia; sete já têm código de negócio dentro.

| Módulo | Estado | O que existe hoje |
|---|---|---|
| `shared` | Implementado | `Money`, `ContaId`, `TenantContext`, `FusoDeReferencia` |
| `contas` | Implementado | `Conta`, `Usuario`, `Credencial`, login por JWT, política de senha, provisionamento de conta, e a pergunta que os outros módulos fazem à conta em operação, como se o controle de estoque está ligado |
| `cadastro` | Implementado | `Produto` com atributos `JSONB`, busca por nome ou código para o balcão, `Cliente` como vertical slice, catálogo sugerido por tipo de negócio, e o agregado inteiro: `MovimentoEstoque` como membro, com a baixa por venda, o estorno por cancelamento e o ajuste manual gravando movimento e saldo na mesma transação, o estoque mínimo de cada produto e a resposta de quais estão com estoque baixo |
| `caixa` | Implementado | `SessaoCaixa`: abertura, sangria, suprimento, fechamento com conferência, histórico por operador e por dia, e o dinheiro em espécie de cada venda concluída entrando na gaveta por evento, uma vez só, mesmo que o evento chegue de novo; e saindo dela, por outro evento, quando a venda é cancelada, num estorno que espelha exatamente o que entrou |
| `pagamentos` | Implementado | Strategy por forma de pagamento: dinheiro com troco calculado no domínio, Pix e cartão lançados à mão pelo operador. Sem provedor de Pix ainda |
| `vendas` | Em andamento | agregado `Venda`, com `ItemVenda` e `Pagamento` como membros: abrir a comanda num caixa aberto, lançar e remover itens com o preço copiado do produto, desconto por item e por venda, total recalculado a cada operação, pagamento dividido entre formas com o troco vindo do módulo de pagamentos, conclusão como passo explícito, que exige o caixa ainda aberto e publica o evento `VendaConcluida` pelo outbox, e cancelamento, que desfaz a comanda aberta ou a venda concluída e, só no segundo caso, publica `VendaCancelada` para o caixa e o estoque desfazerem o que fizeram; e o comprovante não-fiscal de uma venda concluída, devolvido como dado para a tela imprimir ou compartilhar, com as linhas já calculadas, os descontos, as parcelas confirmadas e o troco, que passou a ficar gravado na parcela. Falta o vínculo de cliente |
| `estoque` | Em andamento | o ouvinte da venda concluída: quando a conta ligou o controle de estoque, cada produto vendido vira uma baixa, pedida ao cadastro, dono do agregado; serviço no meio dos itens não gera nada, e o evento entregue de novo não baixa em dobro. E os casos de uso que uma pessoa aciona: ajuste manual com motivo obrigatório, perda, quebra ou contagem, com a diferença carregando o sinal; estoque mínimo por produto; e o alerta de estoque baixo como consulta, a lista dos produtos ativos no mínimo ou abaixo. A conta que não ligou o controle é recusada nos três. E o ouvinte da venda cancelada, imagem espelhada do primeiro: cada produto que a venda baixou volta, uma vez só, e o que nunca saiu não volta |
| `relatorios` | Em andamento | faturamento do dia e de um período, com o dia delimitado no fuso do balcão e pelo instante em que a venda concluiu, contando só as vendas concluídas. O módulo só lê: enxerga a tabela de venda por um mapeamento próprio, imutável, com as colunas que o relatório usa, e o repositório dele nem tem método de escrita. A soma é do banco. Faltam mais vendidos, fluxo de caixa e os filtros |

**Schema.** Doze migrations Flyway, de `V1` a `V12`: conta, usuário e credencial; produto; cliente;
catálogo de referência; o agregado de caixa, com a regra de uma sessão aberta por operador; o
agregado de venda, com a venda, seus itens e seus pagamentos; o outbox de eventos de domínio do
Spring Modulith, cujo DDL foi gerado a partir da entidade do framework em vez de escrito de
memória; o movimento de estoque, o membro que o agregado de produto esperava desde a segunda; o
estoque mínimo de cada produto, o limiar do alerta de estoque baixo; o estorno no caixa, o
quarto tipo de movimento, com as restrições da quinta recriadas para o receber; e o que o
comprovante precisava e a venda não guardava, o troco de cada parcela e o instante da conclusão.

**Superfície HTTP.** Dois endpoints, ambos de autenticação: `POST /api/auth/login`, que devolve o
token, e `GET /api/auth/eu`, que existe para haver um recurso protegido de verdade contra o qual
verificar, por HTTP, que requisição sem token é recusada e que o tenant vem do claim e não do
pedido. Os casos de uso de produto, cliente, caixa, pagamento e venda vivem na camada de aplicação
e são exercitados por teste de integração: a camada `web` de cada módulo nasce junto com o PWA que
vai consumi-la, e não antes, para o contrato HTTP não ser desenhado às cegas.

## Por onde começar a leitura

Atalhos para avaliar o código sem percorrer o repositório inteiro.

| O que olhar | Onde | Por que vale a leitura |
|---|---|---|
| Fronteira de módulo verificada por teste | [ModularityTests.java](src/test/java/br/com/caixasimples/ModularityTests.java) | A arquitetura falha o build quando é violada, em vez de depender de disciplina |
| Isolamento entre contas provado nos dois sentidos | [IsolamentoEntreContasTest.java](src/test/java/br/com/caixasimples/contas/IsolamentoEntreContasTest.java) | Segurança testada, não presumida: grava na conta A e prova que a conta B não enxerga |
| O mecanismo por trás desse isolamento | [MultiTenancyConfiguration.java](src/main/java/br/com/caixasimples/shared/internal/MultiTenancyConfiguration.java) | Filtro no Hibernate, não em cada consulta, e o que acontece quando não há tenant no contexto |
| Raiz de agregado sem framework | [SessaoCaixa.java](src/main/java/br/com/caixasimples/caixa/domain/SessaoCaixa.java) | Regra de negócio e invariantes isoladas de Spring e de JPA |
| Invariante viva, recalculada a cada operação | [Venda.java](src/main/java/br/com/caixasimples/vendas/domain/Venda.java) | O total nunca fica negativo, nunca diverge dos itens e nunca fica abaixo do já pago; a venda só conclui com os pagamentos confirmados iguais ao total. Cada operação que quebraria uma regra é recusada antes de tocar no agregado, e o estado remontado do banco passa pela mesma conferência |
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
| Um módulo que só lê, garantido por construção | [VendaParaRelatorio.java](src/main/java/br/com/caixasimples/relatorios/internal/VendaParaRelatorio.java) | A mesma tabela mapeada uma segunda vez, imutável e só com o que o relatório usa, para os relatórios lerem sem importar o pacote interno de vendas nem remontar o agregado; o repositório ao lado não tem método de escrita, e o porquê de o dia ser o da conclusão está em [FaturamentoService.java](src/main/java/br/com/caixasimples/relatorios/application/FaturamentoService.java) |
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

Nem todo módulo tem as quatro: uma camada nasce quando há o que colocar nela. Hoje só `contas` tem
`web/`, por ser o único com superfície HTTP; `cadastro` acomoda `Cliente` inteiro em `internal/`,
porque um slice sem invariante não precisa de `domain/`; e `relatorios` não tem `domain/` porque
não tem regra a proteger: ele lê colunas e soma.

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

Os listeners de evento moram em `internal/`, por serem adapters de entrada, e não usam a anotação
composta que o Modulith oferece: ela abriria a transação antes de o tenant estar no contexto, e o
Hibernate resolve o tenant na abertura da sessão. O molde do projeto define a conta a partir do
evento e só então abre a transação, com a ordem escrita no próprio arquivo.

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
  dinheiro, Pix e cartão têm uma classe cada, e a regra de cada forma mora no domínio, não no
  componente do Spring.
- **Value object** para dinheiro, com o arredondamento visível em quem o chama.
- **Domain Events com outbox** para a venda concluída, e a cancelada, avisarem o caixa e o
  estoque sem acoplar os três: a publicação é gravada na mesma transação que conclui ou cancela
  a venda, cada listener roda depois do commit, em outra thread, e uma falha deixa a publicação
  incompleta em vez de perder o efeito. Cada evento carrega o fato inteiro, itens e parcelas, e
  quem decide o que fazer com ele é quem ouve: o caixa lê as parcelas, o estoque lê os itens. O
  cancelamento é o espelho da conclusão, com dois ouvintes que desfazem o que os dois primeiros
  fizeram, lançando o movimento oposto em vez de apagar o original.
- **Dependency inversion entre módulos** onde uma pergunta e um evento cruzariam em sentidos
  opostos: a interface mora em quem pergunta, a implementação em quem responde.
- **Vertical slice** onde não há invariante a proteger, em vez de agregado por simetria.
- **Modelo de leitura próprio no módulo que só lê.** Os relatórios não remontam agregado nem
  importam a entidade de outro módulo: mapeiam a mesma tabela uma segunda vez, como entidade
  imutável com só as colunas que a consulta usa, e o repositório herda do marcador do Spring Data
  em vez do completo, sem `save` nem `delete`. O só-leitura fica garantido por construção, em três
  pontos, e não por revisão. A soma é feita no banco, em JPQL, que o filtro de conta alcança.
- **Fitness function de arquitetura**, que transforma a regra de fronteira em teste.

### Padrões decididos, ainda não escritos

Estes estão amarrados a requisitos e a passos que ainda não começaram. Aparecem aqui como desenho,
não como código: **Adapter** para trocar de provedor de Pix sem tocar na regra de negócio, e
**Specification** para os filtros combináveis dos relatórios.

Nenhum deles foi criado por antecipação, e essa é a política do projeto: uma abstração se justifica
com dois usos reais, não com um previsto. O Strategy de pagamento seguiu essa regra: a interface
nasceu junto da primeira implementação, e a fábrica que Pix e cartão compartilham só existe porque
os dois a usam.

## Decisões estruturais

- **Multi-tenancy por coluna.** Toda entidade de negócio tem `contaId` anotado com `@TenantId`, e o
  Hibernate aplica o filtro sozinho. Três exceções deliberadas: `ModeloProduto` (dado de referência
  da plataforma), `Conta` (o `id` dela *é* o tenant) e `Credencial` (consultada no login, antes de
  existir tenant). A tabela do outbox de eventos é do framework, não do negócio, e por isso fica
  fora da lista: a conta a que cada evento se refere viaja dentro dele.
- **Chave primária é UUID gerado na aplicação**, nunca auto-incremento. É o que permite criar um
  registro offline com identidade definitiva e sincronizar depois sem renumerar nada, tornando o
  reenvio de uma operação naturalmente idempotente.
- **O schema pertence ao Flyway.** O Hibernate roda com `ddl-auto: validate` e apenas confere se
  bate. Migration já publicada é imutável: correção é sempre versão nova, como a `V6` é para a `V5`,
  e como a `V7` acrescenta a `movimento_caixa` a chave estrangeira que a `V5` não podia criar,
  porque a tabela de venda ainda não existia. Vale até para tabela que não é do projeto: a do
  outbox do Modulith, na `V8`, foi gerada a partir da entidade do framework, e a suíte prova que o
  gerado bate, porque o contexto não subiria se não batesse.
- **Repositório só para raiz de agregado.** Membro de agregado (item de venda, movimento de caixa,
  movimento de estoque) entra e sai pela raiz, o que impede alterar um item sem recalcular o total
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
- **Regra de negócio entra com o caso de uso, não com a tabela.** O agregado de venda nasceu com
  schema, entidades e repositório e sem caminho de escrita, porque um construtor público sem as
  regras do total seria uma porta lateral. A montagem da comanda trouxe as regras do total; a
  conclusão trouxe as dos pagamentos; o cancelamento trouxe o estado final. O que ainda não tem
  regra, como o vínculo de cliente, continua sem método público, e os testes que precisam de um
  estado específico gravam pelo mesmo método que a entidade usa para remontar o agregado a
  partir do banco.
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
  fechada já foi conferida; a comanda aberta cancela sem perguntar, porque nunca tocou a gaveta,
  e é a saída para a comanda que ficou presa quando o caixa fechou antes. Pix e cartão
  confirmados ficam como estão: não há provedor a quem pedir estorno, e a devolução ao cliente
  acontece no balcão. O estorno pode deixar o esperado negativo se houve sangria no meio, pelo
  mesmo motivo do saldo de estoque negativo: o cancelamento já aconteceu, e recusar só deixaria
  o caixa sem refletir o fato.
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

O isolamento entre contas é tratado como requisito de segurança, e o desenho reflete isso em três
pontos:

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
  produtos, que ele faz ao cadastro, também. O faturamento tem a prova que o plano pediu: duas
  contas com vendas no mesmo dia recebem cada uma só o seu total, e uma terceira, sem venda,
  recebe zero; a consulta agregada em JPQL passa pelo mesmo filtro que as outras.
- **Os listeners de evento agem na conta do evento, não na de quem publicou.** Eles rodam em outra
  thread, sem o tenant da requisição, e uma reentrega pode partir do outbox horas depois; a conta
  vai dentro do evento, lida do contexto autenticado no ato da publicação, e há teste, para os
  quatro ouvintes, que publica como uma conta e prova que o efeito cai na conta do evento e que
  a outra não o vê. A tabela do outbox não tem coluna de conta, porque é do framework e nenhum
  código de negócio a lê.
- **A pergunta sobre a conta não aceita conta.** A tabela de contas é a única sem filtro
  automático, porque o id dela é o tenant; em troca, o serviço que responde por ela lê a conta do
  contexto e nada mais, sem assinatura por onde perguntar sobre outra.

A autenticação usa JWT emitido e validado pelo próprio Spring Security, sem biblioteca de JWT de
terceiro, com API stateless e senha em BCrypt verificada contra bases de senhas vazadas. A política
de senha exige comprimento e nenhuma regra de composição, porque exigir símbolo e maiúscula empurra
o usuário para uma senha pior, anotada num papel no balcão.

A validade do token conta a partir do último contato com o servidor e não do login: toda resposta
autenticada devolve um token renovado. Na prática, é a janela de resistência que a operação offline
exige.

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
| **Venda** | `Venda` | `ItemVenda`, `Pagamento` | `valor_total` reflete a soma dos itens menos o desconto; numa venda concluída, a soma dos pagamentos confirmados é igual ao total | Implementado: as duas regras vivas a cada operação, e conferidas de novo ao remontar o agregado do banco. Cancelada é estado final, alcançado da comanda aberta ou da venda concluída, com itens e parcelas intactos |
| **Caixa** | `SessaoCaixa` | `MovimentoCaixa` | `valor_fechamento_esperado` reflete o valor de abertura mais a soma assinada dos movimentos | Implementado |
| **Produto** | `Produto` | `MovimentoEstoque` | `estoque_atual` reflete a soma dos movimentos, atualizado na mesma transação | Implementado para os três tipos, a saída por venda, a entrada do cancelamento e o ajuste manual: o único método que escreve o saldo exige o movimento junto, o ajuste sem motivo não passa pela raiz, e não se devolve o que não saiu |
| Entidade única | `Conta`, `Usuario`, `Cliente` | nenhum | são agregados de uma entidade só | Implementado |

Referência que cruza agregado é sempre por ID, nunca um `@ManyToOne` navegável. É o que impede
editar um agregado através de outro.

### Detalhes de modelagem que valem nota

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
- **A venda só conclui com os pagamentos confirmados exatamente iguais ao total**, e nunca sem
  item. O troco é calculado pelo módulo de pagamentos, devolvido a quem chamou para a tela mostrar
  no ato, e gravado na parcela, para o comprovante sair igual numa reimpressão. Zero fora de
  dinheiro, e o banco recusa troco em Pix ou cartão.
- **A venda guarda dois instantes:** quando a comanda abriu e quando os pagamentos fecharam a
  conta. O segundo é a data do comprovante, porque numa comanda os dois podem estar horas
  distantes; nasce na conclusão, e o cancelamento não o apaga. É também o que delimita o dia do
  faturamento: a venda conta no dia em que o dinheiro entrou, e uma comanda aberta às 23h50 e paga
  às 00h10 é do dia seguinte. A sessão de caixa, por sua vez, é do dia em que abriu, porque um
  expediente pode atravessar a meia-noite e continua sendo um só.
- **A tabela de venda tem um segundo mapeamento, somente leitura**, no módulo de relatórios:
  imutável, com as colunas que o faturamento usa e nenhuma outra, sem construtor que a instancie.
  O esquema é o contrato entre os dois mapeamentos, versionado nas migrations, e o Hibernate
  valida os dois na subida: renomear uma coluna no módulo de vendas derruba a aplicação no
  deploy, que é o modo certo de falhar, e não com relatório em branco.
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
isolamento, e raiz de agregado tocada exige teste que tenta violar a invariante e espera falha.

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
| Frontend | *planejado*: PWA em React, Vite e TypeScript, com service worker e IndexedDB |
| Infraestrutura | *planejada*: contêiner em AWS |

A escolha de versão não é acidental. Spring Boot 3.x perde suporte OSS em junho de 2026, então um
projeto novo não deveria nascer nele; o Spring Modulith 2.1.x é a linha compatível com o Boot 4.1.

## Estrutura do repositório

```
src
├── main
│   ├── java/br/com/caixasimples
│   │   ├── cadastro/       domain, application, internal
│   │   ├── caixa/          domain, application, internal
│   │   ├── contas/         application, web, internal
│   │   ├── pagamentos/     domain, application, internal
│   │   ├── vendas/         domain, application, internal
│   │   ├── shared/         Money, ContaId, TenantContext, FusoDeReferencia
│   │   ├── estoque/        application, internal
│   │   └── relatorios/     application, internal
│   └── resources
│       └── db/migration/   V1 a V12, imutáveis depois de publicadas
└── test/java/br/com/caixasimples
    ├── ModularityTests     fitness function das fronteiras
    ├── TesteDeIntegracao   base com Testcontainers, herdada pelos testes de banco
    └── ...                 testes por módulo, incluindo isolamento entre contas
```

Todo módulo declarou a fronteira antes de ter código, e o `ModularityTests` a verificava desde
então. Foi assim que a primeira classe de `pagamentos` nasceu dentro de um limite que já existia,
em vez de criar o limite depois do código; `estoque` nasceu do mesmo jeito, com o ouvinte da venda
concluída entrando num pacote cuja fronteira já era testada; e `relatorios`, o último, nasceu com
o faturamento dentro de uma fronteira que já dizia que ele só lê. `estoque` não tem `domain/` nem
tabela própria, e não é omissão: ele é política, a conta participa ou não, e o agregado que ele
move é do cadastro. Os ouvintes em `internal/` e os casos de uso em `application/` decidem e
pedem; quem executa é o dono do agregado. `relatorios` também não tem `domain/` nem tabela: ele
lê as tabelas dos outros por mapeamentos próprios, e ninguém depende dele.

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