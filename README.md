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
primeiro dia; quatro já têm código de negócio dentro.

| Módulo | Estado | O que existe hoje |
|---|---|---|
| `shared` | Implementado | `Money`, `ContaId`, `TenantContext`, `FusoDeReferencia` |
| `contas` | Implementado | `Conta`, `Usuario`, `Credencial`, login por JWT, política de senha, provisionamento de conta |
| `cadastro` | Implementado | `Produto` com atributos `JSONB`, `Cliente` como vertical slice, catálogo sugerido por tipo de negócio |
| `caixa` | Implementado | `SessaoCaixa`: abertura, sangria, suprimento, fechamento com conferência, histórico por operador e por dia |
| `pagamentos` | Próximo | pacote e fronteira declarados, sem código de negócio |
| `vendas` | Planejado | pacote e fronteira declarados, sem código de negócio |
| `estoque` | Planejado | pacote e fronteira declarados, sem código de negócio |
| `relatorios` | Planejado | pacote e fronteira declarados, sem código de negócio |

**Schema.** Seis migrations Flyway, de `V1` a `V6`: conta, usuário e credencial; produto; cliente;
catálogo de referência; e o agregado de caixa, com a regra de uma sessão aberta por operador.

**Superfície HTTP.** Dois endpoints, ambos de autenticação: `POST /api/auth/login`, que devolve o
token, e `GET /api/auth/eu`, que existe para haver um recurso protegido de verdade contra o qual
verificar, por HTTP, que requisição sem token é recusada e que o tenant vem do claim e não do
pedido. Os casos de uso de produto, cliente e caixa vivem na camada de aplicação e são exercitados
por teste de integração: a camada `web` de cada módulo nasce junto com o PWA que vai consumi-la, e
não antes, para o contrato HTTP não ser desenhado às cegas.

## Por onde começar a leitura

Atalhos para avaliar o código sem percorrer o repositório inteiro.

| O que olhar | Onde | Por que vale a leitura |
|---|---|---|
| Fronteira de módulo verificada por teste | [ModularityTests.java](src/test/java/br/com/caixasimples/ModularityTests.java) | A arquitetura falha o build quando é violada, em vez de depender de disciplina |
| Isolamento entre contas provado nos dois sentidos | [IsolamentoEntreContasTest.java](src/test/java/br/com/caixasimples/contas/IsolamentoEntreContasTest.java) | Segurança testada, não presumida: grava na conta A e prova que a conta B não enxerga |
| O mecanismo por trás desse isolamento | [MultiTenancyConfiguration.java](src/main/java/br/com/caixasimples/shared/internal/MultiTenancyConfiguration.java) | Filtro no Hibernate, não em cada consulta, e o que acontece quando não há tenant no contexto |
| Raiz de agregado sem framework | [SessaoCaixa.java](src/main/java/br/com/caixasimples/caixa/domain/SessaoCaixa.java) | Regra de negócio e invariantes isoladas de Spring e de JPA |
| Value object monetário | [Money.java](src/main/java/br/com/caixasimples/shared/Money.java) | Arredondamento explícito no ponto de uso, sem `double` e sem construtor que reescreve valor em silêncio |
| Uma decisão explicada onde ela mora | [FusoDeReferencia.java](src/main/java/br/com/caixasimples/shared/FusoDeReferencia.java) | Por que o fuso é constante única e não coluna, e o que precisa acontecer para reabrir a decisão |
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
`web/`, por ser o único com superfície HTTP, e `cadastro` acomoda `Cliente` inteiro em `internal/`,
porque um slice sem invariante não precisa de `domain/`.

Um módulo nunca importa de `internal/` de outro. Efeito colateral entre módulos é Domain Event;
consulta é chamada direta à API pública do pacote. A regra que resume as duas: *eventos anunciam
fatos, chamadas diretas fazem perguntas.*

### Padrões aplicados

Nenhum padrão entra por simetria, e nenhum entra antes do problema que o justifica. O que está em
uso hoje:

- **Repository apenas por raiz de agregado.** Não existe `MovimentoCaixaRepository`: membro de
  agregado entra e sai pela raiz.
- **Value object** para dinheiro, com o arredondamento visível em quem o chama.
- **Vertical slice** onde não há invariante a proteger, em vez de agregado por simetria.
- **Fitness function de arquitetura**, que transforma a regra de fronteira em teste.

### Padrões decididos, ainda não escritos

Estes estão amarrados a requisitos e a módulos que ainda não começaram. Aparecem aqui como desenho,
não como código: **Strategy** por forma de pagamento, **Adapter** para trocar de provedor de Pix sem
tocar na regra de negócio, **Domain Events** para a venda concluída avisar caixa e estoque sem
acoplar os três, e **Specification** para os filtros combináveis dos relatórios.

Nenhum deles foi criado por antecipação, e essa é a política do projeto: uma abstração se justifica
com dois usos reais, não com um previsto.

## Decisões estruturais

- **Multi-tenancy por coluna.** Toda entidade de negócio tem `contaId` anotado com `@TenantId`, e o
  Hibernate aplica o filtro sozinho. Três exceções deliberadas: `ModeloProduto` (dado de referência
  da plataforma), `Conta` (o `id` dela *é* o tenant) e `Credencial` (consultada no login, antes de
  existir tenant).
- **Chave primária é UUID gerado na aplicação**, nunca auto-incremento. É o que permite criar um
  registro offline com identidade definitiva e sincronizar depois sem renumerar nada, tornando o
  reenvio de uma operação naturalmente idempotente.
- **O schema pertence ao Flyway.** O Hibernate roda com `ddl-auto: validate` e apenas confere se
  bate. Migration já publicada é imutável: correção é sempre versão nova, como a `V6` é para a `V5`.
- **Repositório só para raiz de agregado.** Membro de agregado (item de venda, movimento de caixa)
  entra e sai pela raiz, o que impede alterar um item sem recalcular o total que a raiz garante.
- **Soft delete**, nunca exclusão física, para preservar o histórico de vendas antigas.
- **Dinheiro é um value object `Money`**, `numeric(12,2)` no banco, com arredondamento a duas casas
  visível no ponto de uso. Nada de `double`.
- **O dia é o do balcão.** Timestamps gravados em UTC; a fronteira do dia vem de uma constante única
  da aplicação. Sem isso, todo caixa aberto depois das 21h cairia no dia seguinte do relatório, e
  nada denunciaria o erro.
- **DDD onde existe invariante para proteger.** `SessaoCaixa` e `Produto` têm domínio rico.
  `Cliente`, que não tem invariante, é um vertical slice em um arquivo só. A régua: se acrescentar
  um campo exigir tocar em mais de três ou quatro arquivos, é cerimônia demais.

## Segurança e isolamento

O isolamento entre contas é tratado como requisito de segurança, e o desenho reflete isso em três
pontos:

- **O `contaId` nunca vem do corpo da requisição.** Ele sai do claim do token autenticado, então
  não existe parâmetro que um cliente possa forjar para alcançar dado de outra conta.
- **O filtro é do Hibernate, não de cada consulta.** Uma consulta nova nasce filtrada por
  construção; esquecer o `where` não é uma falha possível. Em contrapartida, o filtro não alcança
  SQL nativo, e por isso query nativa em código de negócio é proibida no projeto.
- **Todo dado persistido tem teste de isolamento**, no molde de gravar na conta A e provar que a
  conta B recebe vazio. Usuário, credencial, produto, cliente e sessão de caixa têm o seu. O teste
  falha se a anotação de tenant for removida.

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
| **Caixa** | `SessaoCaixa` | `MovimentoCaixa` | `valor_fechamento_esperado` reflete o valor de abertura mais a soma assinada dos movimentos | Implementado |
| **Produto** | `Produto` | `MovimentoEstoque` | `estoque_atual` reflete a soma dos movimentos, atualizado na mesma transação | Raiz implementada; o membro nasce com o módulo `estoque` |
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
- **`MovimentoCaixa` tem um campo `tipo`** único para venda, sangria e suprimento, o que mantém o
  fechamento de caixa como uma soma simples em vez de juntar três tabelas. O valor gravado é sempre
  positivo, e quem carrega o sinal é o tipo.
- **`estoque_atual` é consolidado na raiz**, e não somado do histórico a cada leitura, para o alerta
  de estoque baixo não pagar esse preço.

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
│   │   ├── shared/         Money, ContaId, TenantContext, FusoDeReferencia
│   │   ├── estoque/        declarado, sem código de negócio
│   │   ├── pagamentos/     declarado, sem código de negócio
│   │   ├── relatorios/     declarado, sem código de negócio
│   │   └── vendas/         declarado, sem código de negócio
│   └── resources
│       └── db/migration/   V1 a V6, imutáveis depois de publicadas
└── test/java/br/com/caixasimples
    ├── ModularityTests     fitness function das fronteiras
    ├── TesteDeIntegracao   base com Testcontainers, herdada pelos testes de banco
    └── ...                 testes por módulo, incluindo isolamento entre contas
```

Os quatro módulos sem código de negócio não são pastas vazias por descuido: eles declaram a
fronteira desde o início, e o `ModularityTests` já os verifica. É o que faz a primeira classe de
`pagamentos` nascer dentro de um limite que já existe, em vez de criar o limite depois do código.

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