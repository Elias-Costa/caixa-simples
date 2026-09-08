package br.com.caixasimples.cadastro.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio do catalogo sugerido (RF32).
 *
 * <p><strong>E o unico repositorio de {@code cadastro} cujas consultas nao sao filtradas por
 * conta</strong>, porque {@link ModeloProdutoEntity} nao tem {@code @TenantId} — e dado de
 * referencia da plataforma, nao de conta nenhuma. Isso nao e brecha de isolamento nem precedente
 * para mais nada: o que a conta enxerga sao as <em>copias</em> em {@code produto}, essas sim
 * filtradas. Ver {@code .claude/rules/multi-tenancy.md}.
 *
 * <p>Nao existe metodo de escrita alem do {@code save} herdado, e ninguem em producao o chama: as
 * linhas vem da migration (D20a).
 */
public interface ModeloProdutoRepository extends JpaRepository<ModeloProdutoEntity, UUID> {

    /**
     * D20e — ignora maiuscula/minuscula, mesmo tratamento que a P3 da ao e-mail e a D11 ao codigo:
     * {@code Conta.tipo_negocio} aceita texto livre, e {@code Cafeteria} nao pode deixar de casar
     * com {@code cafeteria}. Sustentado pelo indice sobre {@code lower(tipo_negocio)} da {@code V4}.
     */
    List<ModeloProdutoEntity> findByTipoNegocioIgnoreCase(String tipoNegocio);
}
