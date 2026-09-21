import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { useSessao } from '../sessao/useSessao'

export function TelaDeLogin() {
  const { identidade, entrar } = useSessao()
  const navegar = useNavigate()
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  // Quem já está autenticado neste dispositivo não vê o login de novo.
  if (identidade) return <Navigate to="/" replace />

  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await entrar(email, senha)
      navegar('/', { replace: true })
    } catch (falha: unknown) {
      setErro(mensagemDe(falha))
      setEnviando(false)
    }
  }

  return (
    <main className="login">
      <form className="login__cartao" onSubmit={enviar} noValidate>
        <h1 className="login__titulo">Caixa Simples</h1>

        <label className="campo">
          <span className="campo__rotulo">E-mail</span>
          <input
            className="campo__entrada"
            type="email"
            name="email"
            autoComplete="username"
            inputMode="email"
            value={email}
            onChange={(evento) => setEmail(evento.target.value)}
            required
          />
        </label>

        <label className="campo">
          <span className="campo__rotulo">Senha</span>
          <input
            className="campo__entrada"
            type="password"
            name="senha"
            autoComplete="current-password"
            value={senha}
            onChange={(evento) => setSenha(evento.target.value)}
            required
          />
        </label>

        {erro && (
          <p className="login__erro" role="alert">
            {erro}
          </p>
        )}

        <button type="submit" className="botao botao--largo" disabled={enviando}>
          {enviando ? 'Entrando...' : 'Entrar'}
        </button>
      </form>
    </main>
  )
}

function mensagemDe(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem conexão. Verifique a rede e tente de novo.'
  if (falha instanceof ErroDaApi && falha.status === 401) return 'E-mail ou senha incorretos.'
  if (falha instanceof ErroDaApi && falha.detalhe) return falha.detalhe
  return 'Não foi possível entrar. Tente de novo.'
}
