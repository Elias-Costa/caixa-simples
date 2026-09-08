package br.com.caixasimples.caixa.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio da raiz de agregado {@code SessaoCaixa}.
 *
 * <p>Toda consulta aqui e filtrada automaticamente por {@code conta_id} pelo {@code @TenantId} —
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Nao escreva {@code WHERE conta_id} a
 * mao, e nao use query nativa: o filtro do Hibernate nao alcanca SQL nativo.
 *
 * <p><strong>Nasce sem metodo derivado nenhum, e a ausencia e deliberada.</strong> O R06 entrega
 * schema e persistencia, e para isso {@code save}, {@code findById} e {@code findAll} bastam.
 * Consulta por sessao aberta e do R07, e historico por operador e por dia e do R08 — cada uma nasce
 * junto do caso de uso que a usa, nao antes.
 *
 * <p>{@code MovimentoCaixa} e membro do agregado e nunca tera repositorio proprio: e carregado e
 * alterado pela raiz (regra 3 do CLAUDE.md). A entidade dele nem sequer e visivel fora deste
 * pacote, entao a regra nao depende so de disciplina.
 */
public interface SessaoCaixaRepository extends JpaRepository<SessaoCaixaEntity, UUID> {
}
