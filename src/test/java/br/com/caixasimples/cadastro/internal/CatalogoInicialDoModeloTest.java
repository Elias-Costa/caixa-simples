package br.com.caixasimples.cadastro.internal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.CatalogoInicialService;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RF32 pelo lado de dentro: o que so se prova com {@link ModeloProdutoRepository} na mao.
 *
 * <p>Mora em {@code cadastro.internal}, e nao ao lado de {@code CatalogoInicialServiceTest}, pelo
 * mesmo motivo que separou os dois testes de {@code Cliente} no R04: aqui e preciso <em>montar</em>
 * modelo de tipo de negocio, e um catalogo de fabrica so ({@code cafeteria}, D20b) nao provaria que
 * tipos diferentes recebem catalogos diferentes.
 *
 * <p>Os tipos de negocio usados aqui sao ficticios de proposito. Amarrar o teste ao conteudo de
 * fabrica faria cada catalogo novo quebrar um teste que nao e sobre catalogo nenhum.
 */
class CatalogoInicialDoModeloTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ModeloProdutoRepository modelos;

    @Autowired
    private CatalogoInicialService catalogoInicial;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    /** Tipo unico por execucao: modelo_produto e global e nao se limpa entre testes. */
    private static String tipoFicticio(String prefixo) {
        return prefixo + "-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("contas de tipos de negocio diferentes recebem catalogos diferentes")
    void tiposDiferentesRecebemCatalogosDiferentes() {
        String tipoDaPadaria = tipoFicticio("padaria");
        String tipoDaBarbearia = tipoFicticio("barbearia");

        modelos.save(new ModeloProdutoEntity(tipoDaPadaria, "Pao frances", "Panificados", "kg",
                TipoProduto.PRODUTO, Map.of()));
        modelos.save(new ModeloProdutoEntity(tipoDaBarbearia, "Corte de cabelo", "Servicos", "hora",
                TipoProduto.SERVICO, Map.of()));

        ContaCriada padaria = criador.criar("Padaria do Teste", SENHA_DE_TESTE);
        ContaCriada barbearia = criador.criar("Barbearia do Teste", SENHA_DE_TESTE);

        TenantContext.executarComo(padaria.contaId(),
                () -> catalogoInicial.aplicarPara(tipoDaPadaria));
        TenantContext.executarComo(barbearia.contaId(),
                () -> catalogoInicial.aplicarPara(tipoDaBarbearia));

        TenantContext.executarComo(padaria.contaId(), () ->
                assertThat(produtos.listarAtivos())
                        .extracting(Produto::getNome)
                        .containsExactly("Pao frances"));

        TenantContext.executarComo(barbearia.contaId(), () ->
                assertThat(produtos.listarAtivos())
                        .extracting(Produto::getNome)
                        .containsExactly("Corte de cabelo"));
    }

    @Test
    @DisplayName("a copia carrega tipo SERVICO e os atributos sugeridos do jsonb")
    void copiaCarregaTipoEAtributos() {
        String tipo = tipoFicticio("oficina");
        modelos.save(new ModeloProdutoEntity(tipo, "Troca de oleo", "Servicos", "un",
                TipoProduto.SERVICO, Map.of("garantia_dias", 90, "exige_agendamento", true)));

        ContaCriada conta = criador.criar("Oficina do Teste", SENHA_DE_TESTE);
        TenantContext.executarComo(conta.contaId(), () -> catalogoInicial.aplicarPara(tipo));

        TenantContext.executarComo(conta.contaId(), () -> {
            Produto copiado = produtos.listarAtivos().getFirst();

            // D20c — sem a coluna `tipo`, isto viria como PRODUTO e um servico carregaria estoque.
            assertThat(copiado.getTipo()).isEqualTo(TipoProduto.SERVICO);
            assertThat(copiado.getAtributos())
                    .containsEntry("garantia_dias", 90)
                    .containsEntry("exige_agendamento", true);
        });
    }

    @Test
    @DisplayName("editar o produto copiado nao altera a linha de modelo_produto")
    void editarACopiaNaoAlteraOModelo() {
        String tipo = tipoFicticio("floricultura");
        UUID modeloId = modelos.save(new ModeloProdutoEntity(tipo, "Buque de rosas", "Arranjos",
                "un", TipoProduto.PRODUTO, Map.of("cor", "vermelha"))).getId();

        ContaCriada conta = criador.criar("Floricultura do Teste", SENHA_DE_TESTE);
        TenantContext.executarComo(conta.contaId(), () -> catalogoInicial.aplicarPara(tipo));

        TenantContext.executarComo(conta.contaId(), () -> {
            Produto copiado = produtos.listarAtivos().getFirst();
            produtos.editar(copiado.getId(), new DadosDoProduto("Buque grande", Money.de("120.00"),
                    null, "Especiais", "un", Map.of("cor", "branca")));
        });

        // O item copiado passou a pertencer a conta; o modelo e da plataforma e nao se move junto.
        ModeloProdutoEntity modelo = modelos.findById(modeloId).orElseThrow();
        assertThat(modelo.getNome()).isEqualTo("Buque de rosas");
        assertThat(modelo.getCategoria()).isEqualTo("Arranjos");
        assertThat(modelo.getAtributosSugeridos()).containsEntry("cor", "vermelha");
    }

    @Test
    @DisplayName("modelo_produto e lida igual pelas duas contas — a ausencia de filtro e deliberada")
    void modeloProdutoEGlobal() {
        String tipo = tipoFicticio("banca");
        modelos.save(new ModeloProdutoEntity(tipo, "Revista", "Publicacoes", "un",
                TipoProduto.PRODUTO, Map.of()));

        ContaCriada contaA = criador.criar("Banca A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Banca B", SENHA_DE_TESTE);

        // Ao contrario de todo outro repositorio do sistema, este devolve a mesma coisa sob
        // qualquer tenant: modelo_produto e dado da plataforma (.claude/rules/multi-tenancy.md).
        // Se um @TenantId aparecer em ModeloProdutoEntity por engano, e aqui que quebra.
        List<String> pelaContaA = TenantContext.executarComo(contaA.contaId(),
                () -> nomesDoTipo(tipo));
        List<String> pelaContaB = TenantContext.executarComo(contaB.contaId(),
                () -> nomesDoTipo(tipo));

        assertThat(pelaContaA).containsExactly("Revista");
        assertThat(pelaContaB).isEqualTo(pelaContaA);
    }

    private List<String> nomesDoTipo(String tipoNegocio) {
        return modelos.findByTipoNegocioIgnoreCase(tipoNegocio).stream()
                .map(ModeloProdutoEntity::getNome)
                .toList();
    }
}
