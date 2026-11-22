package br.com.controlefinancas.nota;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONTokener;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abre a nota fiscal (NFC-e) num navegador interno do próprio celular, lê o texto da página
 * e entrega ao app Controle de Finanças, que abre o Novo lançamento já preenchido.
 *
 * Se o site recusar o pedido, tenta outras formas de se apresentar (sem o aviso do aplicativo,
 * com a identificação do Chrome, etc.) e, no fim, mostra o resultado de cada tentativa.
 */
public class MainActivity extends Activity {

    private static final Pattern ENDERECO = Pattern.compile("https?://\\S+");
    private static final Pattern NOTA = Pattern.compile("valor a pagar|vl\\.? ?total|qtd\\. total", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CHECAGENS = 6;

    // cada tentativa: {sem o aviso do aplicativo, identificação do Chrome, simula o objeto "chrome" da página}
    private static final boolean[][] TENTATIVAS = {
            {true, false, false},
            {true, true, false},
            {true, true, true},
    };
    private static final String[] NOMES = {
            "sem aviso do app, identificação padrão",
            "sem aviso do app, identificação do Chrome",
            "sem aviso do app, Chrome completo",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder relatorio = new StringBuilder();
    private FrameLayout raiz;
    private WebView web;
    private LinearLayout painel;
    private TextView status;
    private ProgressBar progresso;
    private Button botaoNavegador;
    private Button botaoPagina;
    private String alvo;
    private int tentativa = -1;
    private int checagens = 0;
    private boolean enviado = false;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);

        raiz = new FrameLayout(this);

        painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.VERTICAL);
        painel.setGravity(Gravity.CENTER);
        painel.setBackgroundColor(Color.WHITE);
        painel.setPadding(dp(24), dp(24), dp(24), dp(24));
        raiz.addView(painel, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.parseColor("#0f172a"));
        painel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progresso = new ProgressBar(this);
        painel.addView(progresso, new LinearLayout.LayoutParams(-2, -2));

        botaoNavegador = botao("Abrir no navegador", v -> abrirNoNavegador());
        botaoPagina = botao("Ver a página", v -> painel.setVisibility(View.GONE));
        setContentView(raiz);

        alvo = descobrirEndereco(getIntent());
        if (alvo == null) {
            mostrar("Nota Fiscal\n\nLeia o QR Code do cupom com a câmera do celular e toque no link. Ou, com a nota aberta no navegador, toque em Compartilhar e escolha Nota Fiscal.", false);
            return;
        }
        proxima();
    }

    /** Começa a próxima tentativa com um navegador interno novo (sem cookies da tentativa anterior). */
    private void proxima() {
        if (enviado || isFinishing()) return;
        tentativa++;
        if (tentativa >= TENTATIVAS.length) {
            falhar("Não consegui ler a nota.\n\n" + relatorio);
            return;
        }
        checagens = 0;
        mostrar("Lendo a nota… (tentativa " + (tentativa + 1) + " de " + TENTATIVAS.length + ")", true);
        if (web != null) {
            raiz.removeView(web);
            web.destroy();
        }
        CookieManager.getInstance().removeAllCookies(null);
        web = new WebView(this);
        raiz.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        configurar(web, TENTATIVAS[tentativa]);
        final int minha = tentativa;
        web.loadUrl(alvo);
        handler.postDelayed(() -> verificar(minha), 2500);
    }

    private void configurar(WebView w, boolean[] modo) {
        WebSettings ajustes = w.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        if (modo[1]) {
            ajustes.setUserAgentString(ajustes.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        }
        if (modo[0] && WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // não enviar o cabeçalho que identifica este aplicativo
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ajustes, Collections.<String>emptySet());
        }
        if (modo[2] && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w,
                    "if(!window.chrome){window.chrome={runtime:{},app:{isInstalled:false},loadTimes:function(){},csi:function(){}};}",
                    Collections.singleton("*"));
        }
        final int minha = tentativa;
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest pedido, WebResourceError erro) {
                if (pedido.isForMainFrame() && minha == tentativa) {
                    registrar("não abriu a página (sem internet?)");
                    proxima();
                }
            }
        });
    }

    /** Pergunta à página o texto que ela mostra; se já for a nota, entrega ao app. */
    private void verificar(int deQuem) {
        if (enviado || isFinishing() || deQuem != tentativa) return;
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()", valor -> {
            if (enviado || isFinishing() || deQuem != tentativa) return;
            String texto = decodificar(valor);
            if (texto.length() > 150 && NOTA.matcher(texto).find()) {
                enviar(texto);
                return;
            }
            if (texto.toLowerCase().contains("request rejected")) {
                registrar("recusada pelo site");
                proxima();
                return;
            }
            checagens++;
            if (checagens >= MAX_CHECAGENS) {
                registrar("a página não carregou a tempo");
                proxima();
                return;
            }
            handler.postDelayed(() -> verificar(deQuem), 1500);
        });
    }

    private void registrar(String resultado) {
        relatorio.append("• Tentativa ").append(tentativa + 1).append(" (").append(NOMES[tentativa]).append("): ").append(resultado).append("\n");
    }

    private void enviar(String texto) {
        if (enviado) return;
        enviado = true;
        String url = getString(R.string.app_url) + "/compartilhar#text=" + Uri.encode(texto);
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addCategory(Intent.CATEGORY_BROWSABLE);
            String pwa = acharAplicativoInstalado(it);
            if (pwa != null) it.setPackage(pwa); // abre direto no aplicativo instalado, sem passar pelo navegador
            startActivity(it);
        } catch (Exception e) {
            enviado = false;
            falhar("Não consegui abrir o app Controle de Finanças.");
            return;
        }
        finish();
    }

    /** Procura o Controle de Finanças instalado como aplicativo (os "WebAPK" do Chrome têm "webapk" no nome do pacote). */
    private String acharAplicativoInstalado(Intent it) {
        try {
            List<ResolveInfo> lista = getPackageManager().queryIntentActivities(it, PackageManager.MATCH_ALL);
            for (ResolveInfo r : lista) {
                String pacote = r.activityInfo.packageName;
                if (pacote != null && pacote.toLowerCase().contains("webapk")) return pacote;
            }
        } catch (Exception e) {
            // sem permissão para consultar: segue pelo caminho normal
        }
        return null;
    }

    private void falhar(String mensagem) {
        if (enviado) return;
        mostrar(mensagem, false);
        status.setTextSize(15);
        botaoNavegador.setVisibility(View.VISIBLE);
        botaoPagina.setVisibility(View.VISIBLE);
    }

    private void mostrar(String mensagem, boolean carregando) {
        painel.setVisibility(View.VISIBLE);
        status.setTextSize(17);
        status.setText(mensagem);
        progresso.setVisibility(carregando ? View.VISIBLE : View.GONE);
        botaoNavegador.setVisibility(View.GONE);
        botaoPagina.setVisibility(View.GONE);
    }

    /** Abre a nota no navegador padrão (sem voltar para este app). */
    private void abrirNoNavegador() {
        try {
            Intent it = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
            it.setData(Uri.parse(alvo));
            startActivity(it);
        } catch (Exception e) {
            status.setText("Não encontrei um navegador.");
        }
    }

    private String descobrirEndereco(Intent it) {
        if (it == null) return null;
        String acao = it.getAction();
        if (Intent.ACTION_VIEW.equals(acao) && it.getData() != null) {
            return it.getDataString();
        }
        if (Intent.ACTION_SEND.equals(acao)) {
            String t = it.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                Matcher m = ENDERECO.matcher(t);
                if (m.find()) return m.group();
            }
        }
        return null;
    }

    private String decodificar(String valor) {
        try {
            Object o = new JSONTokener(valor).nextValue();
            return o instanceof String ? (String) o : "";
        } catch (Exception e) {
            return "";
        }
    }

    private Button botao(String texto, View.OnClickListener ao) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setOnClickListener(ao);
        b.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        painel.addView(b, p);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
