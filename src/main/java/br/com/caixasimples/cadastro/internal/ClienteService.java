package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.shared.ContaId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de cliente (RF03), edição (RF04) e inativação (RF05).
 *
 * <p><strong>Este arquivo é o slice inteiro</strong>: caso de uso, entidade e repositório moram
 * aqui, e só o caso de uso é público. Não há {@code domain/} nem mapper para cliente de propósito,
 * porque ele não tem invariante nenhuma a proteger, e dar estrutura de agregado a uma entidade
 * assim seria cerimônia. É o mesmo julgamento já aplicado a {@code Conta} e {@code Usuario}.
 *
 * <p>Mora em {@code internal} porque carrega o {@code @Entity}, e JPA vive em {@code internal}. A
 * camada {@code web} nasce depois no mesmo módulo e alcança esta classe sem violar fronteira;
 * outro módulo não alcança, já que pelo Modulith todo subpacote é interno ao módulo.
 *
 * <p>Não existe busca por nome nem vínculo com venda aqui: a busca durante a venda nasce com o
 * módulo de vendas, e a venda referencia o cliente apenas por UUID.
 */
@Service
public class ClienteService {

    private final ClienteRepository clientes;

    ClienteService(ClienteRepository clientes) {
        this.clientes = clientes;
    }

    /**
     * Cadastro de cliente novo (RF03).
     *
     * @return o id do cliente criado, gerado na aplicação e nunca pelo banco (RNF01, RNF03)
     */
    @Transactional
    public UUID cadastrar(DadosDoCliente dados) {
        Objects.requireNonNull(dados, "dados do cliente nao podem ser nulos");

        return clientes.save(new ClienteEntity(dados.nome(), dados.contato())).getId();
    }

    /**
     * Edição do cadastro (RF04). Substitui os dois campos pelo que veio em {@code dados}.
     *
     * @throws ClienteNaoEncontradoException se o id não existe nesta conta
     * @throws IllegalStateException         se o cliente já foi inativado
     */
    @Transactional
    public void editar(UUID id, DadosDoCliente dados) {
        Objects.requireNonNull(dados, "dados do cliente nao podem ser nulos");

        ClienteEntity linha = buscar(id);
        linha.alterar(dados.nome(), dados.contato());
        clientes.save(linha);
    }

    /**
     * Inativação (RF05): soft delete, nunca {@code DELETE}. O cliente some da listagem e o registro
     * fica, para a venda antiga não perder a referência.
     *
     * <p>É idempotente: inativar de novo o que já está inativo não estoura.
     *
     * @throws ClienteNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional
    public void inativar(UUID id) {
        ClienteEntity linha = buscar(id);
        linha.inativar();
        clientes.save(linha);
    }

    /**
     * Traz de volta um cliente inativado. Idempotente pelo mesmo motivo da inativação.
     *
     * <p>Existe aqui e não existe em produto porque lá a reativação esbarraria no índice único de
     * {@code codigo} entre os ativos, que ela poderia violar. A tabela {@code cliente} não tem
     * índice único nenhum, então a operação não esbarra em nada.
     *
     * @throws ClienteNaoEncontradoException se o id não existe nesta conta
     */
    @Transactional
    public void reativar(UUID id) {
        ClienteEntity linha = buscar(id);
        linha.reativar();
        clientes.save(linha);
    }

    /** Clientes da conta. Cliente inativado não aparece aqui, que é o efeito visível do RF05. */
    @Transactional(readOnly = true)
    public List<Cliente> listarAtivos() {
        return clientes.findByAtivoTrue().stream().map(ClienteEntity::paraCliente).toList();
    }

    private ClienteEntity buscar(UUID id) {
        Objects.requireNonNull(id, "id do cliente nao pode ser nulo");
        return clientes.findById(id).orElseThrow(() -> new ClienteNaoEncontradoException(id));
    }

    /**
     * Os campos de um cliente, na ordem em que uma tela de cadastro os apresenta.
     *
     * <p>Record em vez de dois parâmetros soltos porque os dois são {@code String}: trocar um pelo
     * outro na chamada compilaria em silêncio.
     *
     * @param nome    obrigatório
     * @param contato opcional, telefone ou e-mail; em branco vira ausente
     */
    public record DadosDoCliente(String nome, String contato) {
    }

    /**
     * O que a listagem devolve.
     *
     * <p>Existe para a entidade continuar em visibilidade de pacote: quem chama o caso de uso
     * recebe um valor, não a linha do banco.
     */
    public record Cliente(UUID id, String nome, String contato) {
    }

    /**
     * Não existe cliente com esse id <strong>nesta conta</strong>.
     *
     * <p>Como em {@code ProdutoNaoEncontradoException}, um id de outra conta é indistinguível de um
     * id que nunca existiu: o filtro de {@code @TenantId} faz a linha não voltar do banco (RNF05).
     */
    public static class ClienteNaoEncontradoException extends RuntimeException {

        public ClienteNaoEncontradoException(UUID id) {
            super("cliente nao encontrado nesta conta: " + id);
        }
    }
}

/**
 * Linha da tabela {@code cliente}, com a regra dentro, no mesmo molde de {@code Usuario}.
 *
 * <p>Visibilidade de pacote de propósito: quem está fora do slice fala com {@link ClienteService},
 * nunca com a linha.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente
 * ({@link TenantId}). Não há construtor nem setter que o receba, porque {@code contaId} nunca vem
 * de fora da aplicação (RNF05).
 */
@Entity
@Table(name = "cliente")
class ClienteEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(nullable = false)
    private String nome;

    /** Opcional: telefone ou e-mail. Em branco é gravado como ausente, nunca vazio. */
    private String contato;

    /** Soft delete (RF05): preserva a referência das vendas já registradas para este cliente. */
    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ClienteEntity() {
        // exigido pelo JPA
    }

    ClienteEntity(String nome, String contato) {
        this.id = UUID.randomUUID();
        this.nome = exigirNome(nome);
        this.contato = normalizarContato(contato);
        this.ativo = true;
        this.criadoEm = Instant.now();
    }

    /**
     * Edição (RF04).
     *
     * <p>Recusa cliente inativo: sem reativar antes, a alteração mudaria em silêncio um registro
     * que não aparece em listagem nenhuma.
     */
    void alterar(String nome, String contato) {
        if (!ativo) {
            throw new IllegalStateException(
                    "cliente inativo nao pode ser editado; reative antes: " + id);
        }
        this.nome = exigirNome(nome);
        this.contato = normalizarContato(contato);
    }

    void inativar() {
        this.ativo = false;
    }

    void reativar() {
        this.ativo = true;
    }

    private static String exigirNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do cliente nao pode ser vazio");
        }
        return nome.trim();
    }

    /** Sem contato tem uma representação só: ausente. Nunca a string vazia. */
    private static String normalizarContato(String contato) {
        if (contato == null || contato.isBlank()) {
            return null;
        }
        return contato.trim();
    }

    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha é. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }

    boolean isAtivo() {
        return ativo;
    }

    ClienteService.Cliente paraCliente() {
        return new ClienteService.Cliente(id, nome, contato);
    }
}

/**
 * Repositório do slice.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p>Visibilidade de pacote, porque só o próprio slice consulta cliente. É interface de topo, e não
 * aninhada em {@link ClienteService}, porque o Spring Data só varre repositório aninhado quando
 * configurado para isso.
 */
interface ClienteRepository extends JpaRepository<ClienteEntity, UUID> {

    List<ClienteEntity> findByAtivoTrue();
}
