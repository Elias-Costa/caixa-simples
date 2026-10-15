package br.com.caixasimples.contas.internal;

/**
 * Porta para a checagem de senha em lista de vazamentos públicos.
 *
 * <p>Existe como interface por um motivo concreto, não por precaução: o adapter real faz chamada
 * HTTP externa, e a suíte de testes não pode depender de rede.
 */
public interface VerificadorDeSenhaVazada {

    /**
     * Falha se a senha aparece em vazamento conhecido, e também se não foi possível verificar.
     *
     * <p>As duas falhas têm tipos diferentes porque o desfecho da segunda depende de quem escolheu a
     * senha, e isso a {@link PoliticaDeSenha} sabe, não o adapter: a senha escolhida por outra
     * pessoa é recusada sem a conferência, e a escolhida pela própria pessoa entra.
     *
     * @throws ListaDeVazadasIndisponivelException se a verificação não pôde ser feita
     * @throws SenhaRecusadaException se a senha está vazada
     */
    void exigirNaoVazada(String senha);
}
