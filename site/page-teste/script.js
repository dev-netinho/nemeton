const toast = document.querySelector(".toast");
let toastTimer;

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 1800);
}

async function copyText(value) {
  const text = value.replaceAll("&lt;", "<").replaceAll("&gt;", ">");
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const field = document.createElement("textarea");
    field.value = text;
    field.setAttribute("readonly", "");
    field.style.cssText = "position:fixed;opacity:0";
    document.body.appendChild(field);
    field.select();
    document.execCommand("copy");
    field.remove();
  }
  showToast(`Copiado: ${text}`);
}

function bindCopyButtons(root = document) {
  root.querySelectorAll(".copy-button").forEach((button) => {
    if (button.dataset.copyBound) return;
    button.dataset.copyBound = "true";
    button.addEventListener("click", () => copyText(button.dataset.copy));
  });
}

bindCopyButtons();

const residents = {
  eira: { name: "Eira", role: "GUIA DO NEMETON", portrait: "/assets/npcs/eira.png", quote: "“Antes de partir, pegue seu kit e guarde o caminho de volta. A clareira sempre será uma zona segura.”", commands: ["/menu", "/guia", "/kit", "/mapa"], note: "Comece por /menu. No Bedrock, ele abre uma interface nativa; no Java, organiza os mesmos caminhos." },
  mara: { name: "Mara", role: "MERCADORA", portrait: "/assets/npcs/mara.png", quote: "“Aqui, valor nasce do acordo entre pessoas. Sem banco infinito, sem loja que fabrica riqueza do nada.”", commands: ["/troca <jogador>", "/comercio <jogador>"], note: "A troca só termina quando os dois lados confirmam. A mesma segurança vale para Java e Bedrock." },
  borin: { name: "Borin", role: "MESTRE DOS CLÃS", portrait: "/assets/npcs/borin.png", quote: "“Uma bandeira dá aliados e território. Também avisa ao mundo que você escolheu viver com algum risco.”", commands: ["/clan criar", "/clan claim", "/raid status"], note: "Clãs podem atacar e ser atacados. Raids têm horário, aposta e restauração; não são grife sem regra." },
  tarin: { name: "Tarin", role: "BATEDOR", portrait: "/assets/npcs/tarin.png", quote: "“Depois dos portões começa o survival. Marque seu refúgio, leve uma mochila e não perca a bússola.”", commands: ["/santuario marcar", "/mochila", "/lapide"], note: "O santuário protege até quatro chunks conectados. Você escolhe quem recebe acesso." },
  nara: { name: "Nara", role: "ARTESÃ DO NEMETON+", portrait: "/assets/npcs/nara.png", quote: "“O mundo continua reconhecível. Eu só transformo mineração e bosses em relíquias que dão vontade de usar.”", commands: ["/mods", "/mods itens", "/mods restaurar"], note: "Essências vêm de minérios; corações vêm de eventos. São extras opcionais, não uma obrigação para jogar." },
};

function selectResident(key) {
  const resident = residents[key];
  if (!resident) return;
  document.querySelectorAll(".resident-tab").forEach((tab) => {
    const active = tab.dataset.resident === key;
    tab.classList.toggle("active", active);
    tab.setAttribute("aria-selected", active ? "true" : "false");
  });
  document.querySelector("#dialogue-name").textContent = resident.name;
  document.querySelector("#dialogue-role").textContent = resident.role;
  document.querySelector("#dialogue-quote").textContent = resident.quote;
  document.querySelector("#dialogue-note").textContent = resident.note;
  const avatar = document.querySelector("#dialogue-avatar");
  avatar.src = resident.portrait;
  avatar.alt = `${resident.name}, ${resident.role.toLowerCase()}`;
  const actions = document.querySelector("#dialogue-actions");
  actions.replaceChildren(...resident.commands.map((command) => {
    const button = document.createElement("button");
    button.className = "copy-button";
    button.type = "button";
    button.dataset.copy = command;
    button.textContent = command;
    return button;
  }));
  bindCopyButtons(actions);
}

document.querySelectorAll(".resident-tab").forEach((tab) => {
  tab.addEventListener("click", () => selectResident(tab.dataset.resident));
  tab.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight"].includes(event.key)) return;
    const tabs = [...document.querySelectorAll(".resident-tab")];
    const next = tabs[(tabs.indexOf(tab) + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length];
    event.preventDefault();
    next.focus();
    selectResident(next.dataset.resident);
  });
});
selectResident("eira");

document.querySelectorAll(".command-tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".command-tab").forEach((item) => {
      const active = item === tab;
      item.classList.toggle("active", active);
      item.setAttribute("aria-selected", active ? "true" : "false");
    });
    document.querySelectorAll(".command-page").forEach((page) => page.classList.toggle("active", page.id === tab.dataset.commandTab));
  });
});

if ("IntersectionObserver" in window && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
  const reveal = new IntersectionObserver((entries) => entries.forEach((entry) => {
    if (!entry.isIntersecting) return;
    entry.target.animate([{ opacity: 0, transform: "translateY(18px)" }, { opacity: 1, transform: "translateY(0)" }], { duration: 650, easing: "cubic-bezier(.2,.8,.2,1)", fill: "forwards" });
    reveal.unobserve(entry.target);
  }), { threshold: .08 });
  document.querySelectorAll(".section-heading, .world-panel, .resident-console, .command-book, .join-section").forEach((element) => reveal.observe(element));
}
