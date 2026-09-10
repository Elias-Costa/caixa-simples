package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.cadastro.internal.ProdutoEntity;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Os casos de uso do cadastro de produto contra o banco de verdade: cadastrar (RF01, RF02), editar
 * (RF04) e inativar (RF05).
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

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        TenantContext.executarComo(conta.contaId(), () ->
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

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        TenantContext.executarComo(conta.contaId(), () ->
                produtoService.editar(id, new DadosDoProduto("Cafe coado grande", Money.de("8.00"),
                        "CAF-2", "Quentes", "copo",
                        Map.of("tempo_preparo", 5, "tamanho", "300ml"))));

        TenantContext.executarComo(conta.contaId(), () -> {
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

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.SERVICO,
                        new DadosDoProduto("Corte", Money.de("40.00"), null, null, "hora", null)));

        TenantContext.executarComo(conta.contaId(), () ->
                produtoService.editar(id, new DadosDoProduto("Corte masculino", Money.de("45.00"),
                        null, "Cabelo", "hora", null)));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(produtos.findById(id).orElseThrow().paraDominio().getTipo())
                        .as("o tipo decide se o item participa de estoque; trocá-lo depois "
                                + "deixaria movimento de estoque órfão")
                        .isEqualTo(TipoProduto.SERVICO));
    }

    @Test
    @DisplayName("inativar preserva o registro e tira o produto da listagem ativa (RF05)")
    void inativarPreservaORegistroESomeDaListagem() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        TenantContext.executarComo(conta.contaId(), () -> produtoService.inativar(id));

        TenantContext.executarComo(conta.contaId(), () -> {
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

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        assertThatNoException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () -> {
                    produtoService.inativar(id);
                    produtoService.inativar(id);
                }));
    }

    @Test
    @DisplayName("produto inativo não pode ser editado")
    void editarProdutoInativoERecusado() {
        ContaCriada conta = criador.criar("Oficina da Avenida", SENHA_DE_TESTE);

        UUID id = TenantContext.executarComo(conta.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        TenantContext.executarComo(conta.contaId(), () -> produtoService.inativar(id));

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        produtoService.editar(id, cafe())));
    }

    @Test
    @DisplayName("id inexistente não passa por editar nem por inativar")
    void idInexistenteERecusado() {
        ContaCriada conta = criador.criar("Padaria do Centro", SENHA_DE_TESTE);
        UUID inexistente = UUID.randomUUID();

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        produtoService.editar(inexistente, cafe())));

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        produtoService.inativar(inexistente)));
    }

    @Test
    @DisplayName("produto da conta A não existe para o caso de uso da conta B (RNF05)")
    void produtoDeOutraContaNaoEAlcancavel() {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_DE_TESTE);

        UUID produtoDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                produtoService.cadastrar(TipoProduto.PRODUTO, cafe()));

        // O caso de uso não distingue um id que não existe de um id que é de outra conta, e nem
        // deve distinguir: o @TenantId filtra o findById antes de qualquer regra rodar.
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        produtoService.editar(produtoDaContaA, cafe())));

        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        produtoService.inativar(produtoDaContaA)));

        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(produtoService.listarAtivos()).isEmpty());

        // E o produto da conta A continua intacto: a tentativa da conta B não encostou nele.
        TenantContext.executarComo(contaA.contaId(), () ->
                assertThat(produtoService.listarAtivos())
                        .singleElement()
                        .satisfies(produto -> assertThat(produto.isAtivo()).isTrue()));
    }
}
