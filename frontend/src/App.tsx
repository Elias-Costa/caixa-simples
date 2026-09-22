import { Navigate, Route, Routes } from 'react-router'
import { ExigeAdmin } from './shell/ExigeAdmin'
import { ExigeSessao } from './shell/ExigeSessao'
import { Shell } from './shell/Shell'
import { EmConstrucao } from './telas/EmConstrucao'
import { Inicio } from './telas/Inicio'
import { TelaDeLogin } from './telas/TelaDeLogin'
import { TelaDeProdutos } from './telas/TelaDeProdutos'
import { TelaDeClientes } from './telas/TelaDeClientes'
import { TelaDeCaixa } from './telas/TelaDeCaixa'

/**
 * As rotas do aplicativo. Todas, menos o login, vivem dentro do shell e exigem sessão; as do
 * administrador ficam sob a guarda dele. O servidor não conhece estes caminhos: qualquer um
 * deles, pedido direto, recebe o shell e o roteador escolhe a tela.
 */
export function App() {
  return (
    <Routes>
      <Route path="/entrar" element={<TelaDeLogin />} />

      <Route element={<ExigeSessao />}>
        <Route element={<Shell />}>
          <Route index element={<Inicio />} />
          <Route path="vender" element={<EmConstrucao titulo="Vender" />} />
          <Route path="caixa" element={<TelaDeCaixa />} />
          <Route path="produtos" element={<TelaDeProdutos />} />
          <Route path="clientes" element={<TelaDeClientes />} />

          <Route element={<ExigeAdmin />}>
            <Route path="relatorios" element={<EmConstrucao titulo="Relatórios" />} />
            <Route path="usuarios" element={<EmConstrucao titulo="Usuários" />} />
            <Route path="configuracao" element={<EmConstrucao titulo="Configuração" />} />
          </Route>

          <Route element={<ExigeAdmin comEstoque />}>
            <Route path="estoque" element={<EmConstrucao titulo="Estoque" />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Route>
    </Routes>
  )
}
