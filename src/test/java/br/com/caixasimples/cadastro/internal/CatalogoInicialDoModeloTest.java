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
 * O catálogo inicial sugerido (RF32) pelo lado de dentro: o que só se prova com
 * {@link ModeloProdutoRepository} na mão.
 *
 * <p>Mora em {@code cadastro.internal}, e não ao lado de {@code CatalogoInicialServiceTest}, porque
 * aqui é preciso <em>montar</em> modelo de tipo de negócio, e o catálogo que vem de fábrica não
 * provaria que tipos diferentes recebem catálogos diferentes.
 *
 * <p>Os tipos de negócio usados aqui são fictícios de propósito. Amarrar o teste ao conteúdo de
 * fábrica faria cada catálogo novo quebrar um teste que não é sobre catálogo nenhum.
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

    /** Tipo único por execução: {@code modelo_produto} é global e não se limpa entre testes. */
    private static String tipoFicticio(String prefixo) {
        return prefixo + "-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("contas de tipos de negócio diferentes recebem catálogos diferentes")
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
    @DisplayName("a cópia carrega tipo SERVICO e os atributos sugeridos do jsonb")
    void copiaCarregaTipoEAtributos() {
        String tipo = tipoFicticio("oficina");
        modelos.save(new ModeloProdutoEntity(tipo, "Troca de oleo", "Servicos", "un",
                TipoProduto.SERVICO, Map.of("garantia_dias", 90, "exige_agendamento", true)));

        ContaCriada conta = criador.criar("Oficina do Teste", SENHA_DE_TESTE);
        TenantContext.executarComo(conta.contaId(), () -> catalogoInicial.aplicarPara(tipo));

        TenantContext.executarComo(conta.contaId(), () -> {
            Produto copiado = produtos.listarAtivos().getFirst();

            // Sem a coluna de tipo no modelo, isto viria como PRODUTO e um serviço carregaria
            // estoque que não existe.
            assertThat(copiado.getTipo()).isEqualTo(TipoProduto.SERVICO);
            assertThat(copiado.getAtributos())
                    .containsEntry("garantia_dias", 90)
                    .containsEntry("exige_agendamento", true);
        });
    }

    @Test
    @DisplayName("editar o produto copiado não altera a linha de modelo_produto")
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

        // O item copiado passou a pertencer à conta; o modelo é da plataforma e não se move junto.
        ModeloProdutoEntity modelo = modelos.findById(modeloId).orElseThrow();
        assertThat(modelo.getNome()).isEqualTo("Buque de rosas");
        assertThat(modelo.getCategoria()).isEqualTo("Arranjos");
        assertThat(modelo.getAtributosSugeridos()).containsEntry("cor", "vermelha");
    }

    @Test
    @DisplayName("modelo_produto é lida igual pelas duas contas, e a ausência de filtro é deliberada")
    void modeloProdutoEGlobal() {
        String tipo = tipoFicticio("banca");
        modelos.save(new ModeloProdutoEntity(tipo, "Revista", "Publicacoes", "un",
                TipoProduto.PRODUTO, Map.of()));

        ContaCriada contaA = criador.criar("Banca A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Banca B", SENHA_DE_TESTE);

        // Ao contrário de todo outro repositório do sistema, este devolve a mesma coisa sob
        // qualquer tenant, porque modelo_produto é dado da plataforma. Se um @TenantId aparecer em
        // ModeloProdutoEntity por engano, é aqui que quebra.
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
