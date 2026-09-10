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
     * <p>Falhar quando a verificação está indisponível é deliberado: sem conseguir conferir,
     * nenhuma senha entra. Hoje o custo disso é baixo, porque quem cria conta é o mantenedor, com
     * rede. <strong>No dia em que troca de senha virar autoatendimento, essa decisão tem de ser
     * reaberta</strong>, porque recusar passa a ser fricção real no balcão.
     *
     * @throws SenhaRecusadaException se a senha está vazada ou se a verificação falhou
     */
    void exigirNaoVazada(String senha);
}
