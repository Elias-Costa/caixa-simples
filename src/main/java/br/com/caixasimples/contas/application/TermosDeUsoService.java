package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.AceiteDosTermos;
import br.com.caixasimples.contas.internal.AceiteDosTermosRepository;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O aceite dos termos de uso pela Conta: o que o Caixa Simples faz com os dados que ela registra,
 * aceito por um administrador antes de ela usar o aplicativo, e de novo a cada versão.
 *
 * <p>O aplicativo mostra só a tela dos termos ao administrador enquanto a Conta não aceitou a
 * versão vigente, e nunca bloqueia o operador, para o balcão não parar por causa de texto. O
 * servidor não recusa as outras rotas sem o aceite, como não recusa com a senha provisória: a
 * Conta nova só tem o administrador que vê a tela.
 */
@Service
public class TermosDeUsoService {

    /**
     * A data da versão dos termos que o aplicativo mostra. Muda junto com o texto e com a constante
     * do aplicativo, que um teste confere ser igual a esta; mudá-la pede o aceite de novo a toda
     * Conta.
     */
    public static final String VERSAO_VIGENTE = "2026-10-19";

    private final AceiteDosTermosRepository aceites;
    private final ContaRepository contas;

    TermosDeUsoService(AceiteDosTermosRepository aceites, ContaRepository contas) {
        this.aceites = aceites;
        this.contas = contas;
    }

    /**
     * Se a Conta do contexto ainda não aceitou a versão vigente.
     *
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     */
    @Transactional(readOnly = true)
    public boolean pendentes() {
        TenantContext.exigirAtual();
        return !aceites.existsByVersao(VERSAO_VIGENTE);
    }

    /**
     * Grava o aceite da versão vigente pelo administrador do contexto. Aceitar de novo a mesma
     * versão não grava outra linha.
     *
     * <p>A versão vem do aplicativo, que a mostra junto com o texto lido: um aparelho com o texto
     * antigo recebe a recusa, em vez de gravar o aceite de um texto que ninguém leu. A linha da
     * Conta fica travada, para dois administradores aceitando juntos não disputarem a mesma linha.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws IllegalStateException se a versão não é a vigente
     */
    @Transactional
    public void aceitar(String versao) {
        UsuarioContext.exigirAdmin();
        if (!VERSAO_VIGENTE.equals(versao)) {
            throw new IllegalStateException("Os termos mudaram desde que o aplicativo foi aberto;"
                    + " recarregue para ler a versão vigente.");
        }
        ContaId contaId = TenantContext.exigirAtual();
        contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        if (aceites.existsByVersao(VERSAO_VIGENTE)) {
            return;
        }
        UUID quemAceita = UsuarioContext.exigirAtual().usuarioId();
        aceites.save(new AceiteDosTermos(VERSAO_VIGENTE, quemAceita, Instant.now()));
    }
}
