package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio da raiz de agregado {@code SessaoCaixa}.
 *
 * <p>Toda consulta aqui e filtrada automaticamente por {@code conta_id} pelo {@code @TenantId} —
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Nao escreva {@code WHERE conta_id} a
 * mao, e nao use query nativa: o filtro do Hibernate nao alcanca SQL nativo.
 *
 * <p><strong>Cada metodo derivado nasce junto do caso de uso que o usa, nunca antes.</strong> O R06
 * nao trouxe nenhum: {@code save}, {@code findById} e {@code findAll} bastavam para schema e
 * persistencia. O R07 trouxe o primeiro, {@link #existsByUsuarioIdAndStatus}. O historico por
 * operador e por dia e do R08.
 *
 * <p>{@code MovimentoCaixa} e membro do agregado e nunca tera repositorio proprio: e carregado e
 * alterado pela raiz (regra 3 do CLAUDE.md). A entidade dele nem sequer e visivel fora deste
 * pacote, entao a regra nao depende so de disciplina.
 */
public interface SessaoCaixaRepository extends JpaRepository<SessaoCaixaEntity, UUID> {

    /**
     * D22a — uma sessao ABERTA por operador. Sustenta a guarda de
     * {@code SessaoCaixaService.abrir}; a rede embaixo, para duas requisicoes simultaneas, e o
     * indice unico parcial da V6.
     *
     * <p>Sem {@code conta_id} na assinatura, como todo o resto: o {@code @TenantId} filtra, entao
     * o operador de outra conta nunca entra nesta contagem (RNF05).
     */
    boolean existsByUsuarioIdAndStatus(UUID usuarioId, StatusSessaoCaixa status);
}
