package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService.ProdutoParaComprovante;
import br.com.caixasimples.cadastro.application.ProdutoService.ProdutoParaVenda;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Os casos de uso do cadastro de produto contra o banco de verdade: cadastrar (RF01, RF02), editar
 * (RF04), inativar (RF05), dar baixa por venda (RF18), ajustar à mão (RF19) e listar o estoque
 * baixo (RF20).
 *
 * <p>A baixa aponta para uma venda de verdade, porque {@code movimento_estoque.venda_id} é chave
 * estrangeira; por isso os testes dela abrem um caixa e gravam uma venda vazia pelas fixtures.
 * O que se lê depois é o saldo pelo domínio; o movimento em si, que só o pacote interno enxerga,
 * é conferido em {@code MovimentoEstoqueDoAgregadoTest}.
 *
 * <p>Roda sobre PostgreSQL real, e não com repositório falso, porque metade do que se quer provar
 * só existe no banco: o {@code jsonb} de ida e volta, o soft delete preservando a linha e o filtro
 * de {@code @TenantId} escondendo do próprio caso de uso o produto de outra conta.
 */
class ProdutoServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ProdutoService produtoService;

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private SessaoCaixaService sessoesDeCaixa;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    private static DadosDoProduto cafe() {
        return new DadosDoProduto("Cafe coado", Money.de("6.50"), "CAF-1", "Bebidas", "un",
                Map.of("tempo_preparo", 3));
    }

    @Test
    @DisplayName("cadastrar grava o produto com os atributos e ele aparece na listagem ativa")
    void cadastrarGravaEListaOProduto() {
        ContaCriada conta = criador.criar("Cafeteria do Centro", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        conta.comoUsuario(() ->
                assertThat(produtoService.listarAtivos())
                        .singleElement()
                        .satisfies(produto -> {
                            assertThat(produto.getId()).isEqualTo(id);
                            assertThat(produto.getNome()).isEqualTo("Cafe coado");
                            assertThat(produto.getPreco()).isEqualTo(Money.de("6.50"));
                            assertThat(produto.getCodigo()).isEqualTo("CAF-1");
                            assertThat(produto.getCategoria()).isEqualTo("Bebidas");
                            assertThat(produto.getUnidade()).isEqualTo("un");
                            assertThat(produto.getTipo()).isEqualTo(TipoProduto.PRODUTO);
                            // O atributo do nicho volta do jsonb sem schema novo (RF02).
                            assertThat(produto.getAtributos()).containsEntry("tempo_preparo", 3);
                            assertThat(produto.isAtivo()).isTrue();
                        }));
    }

    @Test
    @DisplayName("editar persiste os campos novos e substitui os atributos por inteiro (RF04)")
    void editarPersisteOsCamposNovos() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        conta.comoUsuario(() ->
                produtoService.editar(id, new DadosDoProduto("Cafe coado grande", Money.de("8.00"),
                        "CAF-2", "Quentes", "copo",
                        Map.of("tempo_preparo", 5, "tamanho", "300ml"))));

        conta.comoUsuario(() -> {
            Produto lido = produtos.findById(id).orElseThrow().paraDominio();

            assertThat(lido.getNome()).isEqualTo("Cafe coado grande");
            assertThat(lido.getPreco()).isEqualTo(Money.de("8.00"));
            assertThat(lido.getCodigo()).isEqualTo("CAF-2");
            assertThat(lido.getCategoria()).isEqualTo("Quentes");
            assertThat(lido.getUnidade()).isEqualTo("copo");
            assertThat(lido.getAtributos())
                    .as("atributo é substituído por inteiro, nunca mesclado")
                    .containsOnlyKeys("tempo_preparo", "tamanho")
                    .containsEntry("tempo_preparo", 5);
        });
    }

    @Test
    @DisplayName("editar preserva o tipo gravado, porque não há por onde trocá-lo")
    void editarPreservaOTipo() {
        ContaCriada conta = criador.criar("Salao da Praca", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.SERVICO,
                        new DadosDoProduto("Corte", Money.de("40.00"), null, null, "hora", null)));

        conta.comoUsuario(() ->
                produtoService.editar(id, new DadosDoProduto("Corte masculino", Money.de("45.00"),
                        null, "Cabelo", "hora", null)));

        conta.comoUsuario(() ->
                assertThat(produtos.findById(id).orElseThrow().paraDominio().getTipo())
                        .as("o tipo decide se o item participa de estoque; trocá-lo depois "
                                + "deixaria movimento de estoque órfão")
                        .isEqualTo(TipoProduto.SERVICO));
    }

    @Test
    @DisplayName("inativar preserva o registro e tira o produto da listagem ativa (RF05)")
    void inativarPreservaORegistroESomeDaListagem() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        conta.comoUsuario(() -> produtoService.inativar(id));

        conta.comoUsuario(() -> {
            // Soft delete, nunca DELETE: a linha continua lá para o histórico de vendas.
            assertThat(produtos.findById(id))
                    .as("o registro não foi apagado")
                    .get()
                    .extracting(ProdutoEntity::isAtivo)
                    .isEqualTo(false);

            assertThat(produtoService.listarAtivos())
                    .as("mas sumiu do catálogo ativo")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("inativar duas vezes não estoura")
    void inativarEIdempotente() {
        ContaCriada conta = criador.criar("Quitanda do Bairro", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        assertThatNoException().isThrownBy(() ->
                conta.comoUsuario(() -> {
                    produtoService.inativar(id);
                    produtoService.inativar(id);
                }));
    }

    @Test
    @DisplayName("produto inativo não pode ser editado")
    void editarProdutoInativoERecusado() {
        ContaCriada conta = criador.criar("Oficina da Avenida", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        conta.comoUsuario(() -> produtoService.inativar(id));

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.editar(id, cafe())));
    }

    @Test
    @DisplayName("id inexistente não passa por editar nem por inativar")
    void idInexistenteERecusado() {
        ContaCriada conta = criador.criar("Padaria do Centro", SENHA_DE_TESTE);
        UUID inexistente = UUID.randomUUID();

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.editar(inexistente, cafe())));

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.inativar(inexistente)));
    }

    @Test
    @DisplayName("produto da conta A não existe para o caso de uso da conta B (RNF05)")
    void produtoDeOutraContaNaoEAlcancavel() {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_DE_TESTE);

        UUID produtoDaContaA = contaA.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        // O caso de uso não distingue um id que não existe de um id que é de outra conta, e nem
        // deve distinguir: o @TenantId filtra o findById antes de qualquer regra rodar.
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        produtoService.editar(produtoDaContaA, cafe())));

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        produtoService.inativar(produtoDaContaA)));

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        produtoService.consultarParaVenda(produtoDaContaA)));

        // A consulta em lote passa por findAllById, que também é filtrado: o id da conta A não
        // volta, e a falta dele é recusada como inexistente, não devolvida como lista menor.
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        produtoService.consultarParaComprovante(List.of(produtoDaContaA))));

        contaB.comoUsuario(() -> {
            assertThat(produtoService.listarAtivos()).isEmpty();
            assertThat(produtoService.buscarPorNomeOuCodigo("cafe")).isEmpty();
            assertThat(produtoService.buscarPorNomeOuCodigo("CAF-1")).isEmpty();
        });

        // E o produto da conta A continua intacto: a tentativa da conta B não encostou nele.
        contaA.comoUsuario(() ->
                assertThat(produtoService.listarAtivos())
                        .singleElement()
                        .satisfies(produto -> assertThat(produto.isAtivo()).isTrue()));
    }

    @Test
    @DisplayName("busca por nome acha quem contém o termo, sem olhar caixa, só entre ativos (RF06)")
    void buscaPorNomeContemOTermo() {
        ContaCriada conta = criador.criar("Cafeteria da Praca", SENHA_DE_TESTE);

        conta.comoUsuario(() -> {
            produtoService.cadastrar(TipoProduto.PRODUTO, produto("Cafe com leite", null));
            produtoService.cadastrar(TipoProduto.PRODUTO, produto("Cafe coado", null));
            produtoService.cadastrar(TipoProduto.PRODUTO, produto("Pao de queijo", null));
            UUID inativo = produtoService.cadastrar(TipoProduto.PRODUTO,
                    produto("Cafe gelado", null));
            produtoService.inativar(inativo);
        });

        conta.comoUsuario(() -> {
            // O termo está no meio do nome e em caixa diferente; o inativo não aparece.
            assertThat(produtoService.buscarPorNomeOuCodigo("LEITE"))
                    .extracting(Produto::getNome)
                    .containsExactly("Cafe com leite");

            assertThat(produtoService.buscarPorNomeOuCodigo("cafe"))
                    .as("em ordem alfabética, sem o inativo")
                    .extracting(Produto::getNome)
                    .containsExactly("Cafe coado", "Cafe com leite");

            assertThat(produtoService.buscarPorNomeOuCodigo("xyz")).isEmpty();
        });
    }

    @Test
    @DisplayName("busca por código casa inteiro, sem olhar caixa, e vem antes dos nomes (RF06)")
    void buscaPorCodigoCasaInteiroEVemPrimeiro() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);

        conta.comoUsuario(() -> {
            produtoService.cadastrar(TipoProduto.PRODUTO, produto("Agua 12 litros", "500"));
            produtoService.cadastrar(TipoProduto.PRODUTO, produto("Refrigerante", "12"));
            produtoService.cadastrar(TipoProduto.PRODUTO, produto("Suco", "120"));
        });

        conta.comoUsuario(() -> {
            // O código 12 bate inteiro e vem primeiro; 120 não bate por prefixo; e o nome que
            // contém 12 vem depois, pelo caminho do nome.
            assertThat(produtoService.buscarPorNomeOuCodigo("12"))
                    .extracting(Produto::getNome)
                    .containsExactly("Refrigerante", "Agua 12 litros");

            assertThat(produtoService.buscarPorNomeOuCodigo("abc-1"))
                    .as("código ignora maiúsculas, como o índice da migration")
                    .isEmpty();
        });

        // Um produto que bate pelo código e pelo nome aparece uma vez só.
        conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, produto("Lote ABC-1", "abc-1")));

        conta.comoUsuario(() ->
                assertThat(produtoService.buscarPorNomeOuCodigo("ABC-1"))
                        .extracting(Produto::getNome)
                        .containsExactly("Lote ABC-1"));
    }

    @Test
    @DisplayName("busca com termo em branco é recusada: o catálogo inteiro é listarAtivos")
    void buscaComTermoEmBrancoERecusada() {
        ContaCriada conta = criador.criar("Barbearia Central", SENHA_DE_TESTE);

        conta.comoUsuario(() -> {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> produtoService.buscarPorNomeOuCodigo(""));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> produtoService.buscarPorNomeOuCodigo("   "));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> produtoService.buscarPorNomeOuCodigo(null));
        });
    }

    @Test
    @DisplayName("consultarParaVenda devolve o preço vigente e se o produto está ativo")
    void consultarParaVendaDevolvePrecoEEstado() {
        ContaCriada conta = criador.criar("Mercado do Bairro", SENHA_DE_TESTE);

        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        conta.comoUsuario(() -> {
            ProdutoParaVenda ativo = produtoService.consultarParaVenda(id);
            assertThat(ativo.id()).isEqualTo(id);
            assertThat(ativo.preco()).isEqualTo(Money.de("6.50"));
            assertThat(ativo.ativo()).isTrue();
        });

        conta.comoUsuario(() -> produtoService.inativar(id));

        // Inativo volta como inativo, e não como erro: quem decide se ele entra na venda é o
        // módulo de vendas.
        conta.comoUsuario(() ->
                assertThat(produtoService.consultarParaVenda(id).ativo()).isFalse());

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.consultarParaVenda(UUID.randomUUID())));
    }

    @Test
    @DisplayName("consultarParaComprovante devolve nome e unidade de vários produtos numa chamada, inclusive inativo")
    void consultarParaComprovanteDevolveNomeEUnidadeEmLote() {
        ContaCriada conta = criador.criar("Emporio da Serra", SENHA_DE_TESTE);

        UUID cafeId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));
        UUID corteId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.SERVICO, new DadosDoProduto("Corte simples",
                        Money.de("30.00"), null, null, null, null)));
        conta.comoUsuario(() -> produtoService.inativar(corteId));

        // Inativo volta como qualquer outro: o comprovante de uma venda passada precisa do nome
        // mesmo que o item já tenha saído do catálogo. Id repetido conta uma vez.
        conta.comoUsuario(() ->
                assertThat(produtoService.consultarParaComprovante(
                        List.of(cafeId, corteId, cafeId)))
                        .extracting(ProdutoParaComprovante::id, ProdutoParaComprovante::nome,
                                ProdutoParaComprovante::unidade)
                        .containsExactlyInAnyOrder(
                                tuple(cafeId, "Cafe coado", "un"),
                                tuple(corteId, "Corte simples", null)));

        // Um id que não existe no meio do lote derruba a consulta inteira, como na unitária: uma
        // linha sem nome no comprovante seria pior que a falha.
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.consultarParaComprovante(
                                List.of(cafeId, UUID.randomUUID()))));

        conta.comoUsuario(() ->
                assertThat(produtoService.consultarParaComprovante(List.of())).isEmpty());
    }


    @Test
    @DisplayName("a baixa por venda desce o saldo, e a mesma venda não baixa duas vezes")
    void baixaPorVendaDesceOSaldoUmaVezSo() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID cafeId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        conta.comoUsuario(() -> {
            assertThat(produtoService.jaDeuBaixaPorVenda(cafeId, vendaId)).isFalse();
            produtoService.darBaixaPorVenda(cafeId, new BigDecimal("2"), vendaId);
        });

        conta.comoUsuario(() -> {
            assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2");
            assertThat(produtoService.jaDeuBaixaPorVenda(cafeId, vendaId)).isTrue();
        });

        // A mesma venda não baixa duas vezes: a segunda chamada é recusada, e o saldo não se
        // move.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        produtoService.darBaixaPorVenda(cafeId, new BigDecimal("2"), vendaId)))
                .withMessageContaining("ja deu baixa");
        conta.comoUsuario(() ->
                assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2"));
    }

    @Test
    @DisplayName("vendas diferentes baixam o mesmo produto, e o saldo acompanha cada uma")
    void vendasDiferentesBaixamOMesmoProduto() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID queijoId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, produto("Queijo minas", null)));
        UUID sessaoId = abrirCaixa(conta);
        UUID primeiraVenda = vendaEm(conta, sessaoId);
        UUID segundaVenda = vendaEm(conta, sessaoId);

        conta.comoUsuario(() -> {
            produtoService.darBaixaPorVenda(queijoId, new BigDecimal("0.750"), primeiraVenda);
            produtoService.darBaixaPorVenda(queijoId, new BigDecimal("1.250"), segundaVenda);
        });

        conta.comoUsuario(() ->
                assertThat(saldoDe(queijoId)).isEqualByComparingTo("-2.000"));
    }

    @Test
    @DisplayName("serviço vendido não gera movimento nem muda saldo, e não é erro")
    void servicoNaoGeraMovimento() {
        ContaCriada conta = criador.criar("Salao Vizinho", SENHA_DE_TESTE);
        UUID corteId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.SERVICO, produto("Corte", null)));
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        assertThatNoException().isThrownBy(() -> conta.comoUsuario(() ->
                produtoService.darBaixaPorVenda(corteId, BigDecimal.ONE, vendaId)));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(corteId)).isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(corteId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("produto inativado ainda recebe baixa: a venda aconteceu antes")
    void produtoInativoAindaRecebeBaixa() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));
        conta.comoUsuario(() -> produtoService.inativar(id));

        conta.comoUsuario(() ->
                produtoService.darBaixaPorVenda(id, BigDecimal.ONE, vendaId));

        conta.comoUsuario(() ->
                assertThat(saldoDe(id)).isEqualByComparingTo("-1"));
    }

    @Test
    @DisplayName("baixa em produto que não existe nesta conta é recusada com a exceção do cadastro")
    void baixaEmProdutoInexistenteERecusada() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.darBaixaPorVenda(UUID.randomUUID(), BigDecimal.ONE,
                                vendaId)));
        // A pergunta sobre um produto inexistente responde falso em vez de estourar.
        conta.comoUsuario(() ->
                assertThat(produtoService.jaDeuBaixaPorVenda(UUID.randomUUID(), vendaId))
                        .isFalse());
    }

    @Test
    @DisplayName("quantidade da baixa tem de ser positiva, e a recusa não move o saldo")
    void baixaExigeQuantidadePositiva() {
        ContaCriada conta = criador.criar("Emporio do Bairro", SENHA_DE_TESTE);
        UUID id = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        produtoService.darBaixaPorVenda(id, BigDecimal.ZERO, vendaId)))
                .withMessageContaining("positiva");

        conta.comoUsuario(() -> {
            assertThat(saldoDe(id)).isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(id, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("o ajuste manual soma a diferença com sinal ao saldo, nos dois sentidos (RF19)")
    void ajusteManualMoveOSaldoNosDoisSentidos() {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA_DE_TESTE);
        UUID arrozId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, produto("Arroz", null)));

        conta.comoUsuario(() -> {
            produtoService.ajustarEstoque(arrozId, new BigDecimal("20"), "contagem inicial");
            produtoService.ajustarEstoque(arrozId, new BigDecimal("-2.500"), "saco rasgado");
        });

        conta.comoUsuario(() ->
                assertThat(saldoDe(arrozId)).isEqualByComparingTo("17.500"));
    }

    @Test
    @DisplayName("ajuste sem motivo é recusado, e a recusa não move o saldo (RF19)")
    void ajusteSemMotivoERecusado() {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA_DE_TESTE);
        UUID paoId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, produto("Pao frances", null)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        produtoService.ajustarEstoque(paoId, new BigDecimal("-1"), "  ")))
                .withMessageContaining("motivo");

        conta.comoUsuario(() ->
                assertThat(saldoDe(paoId)).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("ajuste e estoque mínimo em produto que não existe nesta conta saem com a exceção do cadastro")
    void ajusteEMinimoEmProdutoInexistenteSaoRecusados() {
        ContaCriada conta = criador.criar("Loja Aurora", SENHA_DE_TESTE);

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.ajustarEstoque(UUID.randomUUID(), BigDecimal.ONE,
                                "contagem")));
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                conta.comoUsuario(() ->
                        produtoService.definirEstoqueMinimo(UUID.randomUUID(), BigDecimal.ONE)));
    }

    @Test
    @DisplayName("o estoque mínimo persiste sem mexer no saldo nem no resto do cadastro")
    void estoqueMinimoPersiste() {
        ContaCriada conta = criador.criar("Emporio Aurora", SENHA_DE_TESTE);
        UUID cafeId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        conta.comoUsuario(() ->
                produtoService.definirEstoqueMinimo(cafeId, new BigDecimal("3.500")));

        conta.comoUsuario(() -> {
            Produto gravado = produtos.findById(cafeId).orElseThrow().paraDominio();
            assertThat(gravado.getEstoqueMinimo()).isEqualByComparingTo("3.500");
            assertThat(gravado.getEstoqueAtual()).isEqualByComparingTo("0");
            assertThat(gravado.getNome()).isEqualTo("Cafe coado");
            assertThat(gravado.getAtributos()).containsEntry("tempo_preparo", 3);
        });
    }

    @Test
    @DisplayName("a lista de estoque baixo traz quem está no mínimo ou abaixo, só produto ativo (RF20)")
    void listaDeEstoqueBaixo() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID zeradoId = cadastrarProduto(conta, "Leite");
        UUID negativoId = cadastrarProduto(conta, "Acucar");
        UUID noLimiarId = cadastrarProduto(conta, "Cafe em graos");
        UUID acimaId = cadastrarProduto(conta, "Chocolate");
        UUID inativoId = cadastrarProduto(conta, "Adocante");
        UUID corteId = conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.SERVICO, produto("Corte", null)));
        UUID vendaId = vendaEm(conta, abrirCaixa(conta));

        conta.comoUsuario(() -> {
            produtoService.darBaixaPorVenda(negativoId, new BigDecimal("2"), vendaId);
            produtoService.ajustarEstoque(noLimiarId, new BigDecimal("5"), "contagem");
            produtoService.definirEstoqueMinimo(noLimiarId, new BigDecimal("5"));
            produtoService.ajustarEstoque(acimaId, new BigDecimal("6"), "contagem");
            produtoService.definirEstoqueMinimo(acimaId, new BigDecimal("5"));
            produtoService.inativar(inativoId);
        });

        conta.comoUsuario(() ->
                assertThat(produtoService.listarComEstoqueBaixo())
                        .extracting(EstoqueDoProduto::id)
                        .as("zerado, negativo e no limiar entram; acima, inativo e serviço não")
                        .containsExactlyInAnyOrder(zeradoId, negativoId, noLimiarId)
                        .doesNotContain(acimaId, inativoId, corteId));

        conta.comoUsuario(() -> {
            EstoqueDoProduto negativo = produtoService.listarComEstoqueBaixo().stream()
                    .filter(item -> item.id().equals(negativoId))
                    .findFirst()
                    .orElseThrow();
            assertThat(negativo.nome()).isEqualTo("Acucar");
            assertThat(negativo.unidade()).isEqualTo("un");
            assertThat(negativo.estoqueAtual()).isEqualByComparingTo("-2");
            assertThat(negativo.estoqueMinimo()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("o operador não cadastra, edita nem inativa produto, mas consulta e busca (RF30)")
    void operadorSoConsulta() {
        ContaCriada conta = criador.criar("Mercearia com Atendente", SENHA_DE_TESTE);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        UUID id = cadastrarProduto(conta, "Arroz");

        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() ->
                        produtoService.cadastrar(TipoProduto.PRODUTO, produto("Feijao", null))));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() ->
                        produtoService.editar(id, produto("Arroz integral", null))));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> produtoService.inativar(id)));

        // O balcão precisa achar o produto para vender: a leitura é dos dois perfis.
        operador.comoUsuario(() -> {
            assertThat(produtoService.listarAtivos())
                    .extracting(Produto::getNome)
                    .containsExactly("Arroz");
            assertThat(produtoService.buscarPorNomeOuCodigo("arr"))
                    .extracting(Produto::getId)
                    .containsExactly(id);
            assertThat(produtoService.consultarParaVenda(id).preco()).isEqualTo(Money.de("5.00"));
        });
    }

    private UUID cadastrarProduto(ContaCriada conta, String nome) {
        return conta.comoUsuario(() ->
                produtoService.cadastrar(TipoProduto.PRODUTO, produto(nome, null)));
    }

    /** O saldo como o domínio o vê: a única leitura pública de {@code estoque_atual} hoje. */
    private BigDecimal saldoDe(UUID produtoId) {
        return produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }

    /** Um caixa aberto, uma vez por conta: um operador só tem uma sessão ABERTA por vez. */
    private UUID abrirCaixa(ContaCriada conta) {
        return conta.comoUsuario(() ->
                sessoesDeCaixa.abrir(Money.ZERO));
    }

    /** Uma venda vazia nesse caixa: só o alvo da chave estrangeira de {@code venda_id}. */
    private UUID vendaEm(ContaCriada conta, UUID sessaoId) {
        return vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
    }

    private static DadosDoProduto produto(String nome, String codigo) {
        return new DadosDoProduto(nome, Money.de("5.00"), codigo, null, "un", null);
    }
}
