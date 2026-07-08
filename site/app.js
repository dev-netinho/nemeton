const toast = document.querySelector(".toast");
let toastTimer;

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 1800);
}

async function copyText(text) {
  const plainText = text.replaceAll("&lt;", "<").replaceAll("&gt;", ">");
  try {
    await navigator.clipboard.writeText(plainText);
  } catch {
    const field = document.createElement("textarea");
    field.value = plainText;
    field.setAttribute("readonly", "");
    field.style.cssText = "position:fixed;opacity:0";
    document.body.appendChild(field);
    field.select();
    document.execCommand("copy");
    field.remove();
  }
  showToast(`Copiado: ${plainText}`);
}

function bindCopyButtons(root = document) {
  root.querySelectorAll("[data-copy]").forEach((button) => {
    if (button.dataset.copyBound) return;
    button.dataset.copyBound = "true";
    button.addEventListener("click", () => copyText(button.dataset.copy));
  });
}

bindCopyButtons();

const residents = {
  eira: {
    name: "Eira",
    role: "Guia do Nemeton",
    quote: "“Antes de partir, pegue seu kit e guarde o caminho de volta. A clareira sempre será uma zona segura.”",
    commands: ["/menu", "/guia", "/kit", "/mapa", "/nemeton"],
    tip: "Comece por /menu. No Bedrock, ele abre uma interface nativa; no Java, organiza os mesmos caminhos.",
  },
  mara: {
    name: "Mara",
    role: "Mercadora",
    quote: "“Aqui, valor nasce do acordo entre pessoas. Sem banco infinito, sem loja que fabrica riqueza do nada.”",
    commands: ["/troca <jogador>", "/comercio <jogador>"],
    tip: "A troca só termina quando os dois lados confirmam. No Bedrock, o fluxo usa uma interface mais segura para evitar desconexões.",
  },
  borin: {
    name: "Borin",
    role: "Mestre dos Clãs",
    quote: "“Uma bandeira dá aliados e território. Também avisa ao mundo que você escolheu viver com algum risco.”",
    commands: ["/clan criar", "/clan convidar", "/clan claim", "/clan chat", "/raid status"],
    tip: "Clãs podem atacar e ser atacados. Raids têm horário, aposta e restauração; não são grife sem regra.",
  },
  tarin: {
    name: "Tarin",
    role: "Batedor",
    quote: "“Depois dos portões começa o survival. Marque seu refúgio, leve uma mochila e não perca a bússola.”",
    commands: ["/santuario marcar", "/santuario confiar", "/mochila", "/lapide"],
    tip: "O santuário protege até quatro chunks conectados. Você pode liberar e remover pessoas de confiança quando quiser.",
  },
  nara: {
    name: "Nara",
    role: "Artesã do Nemeton+",
    quote: "“O mundo continua reconhecível. Eu apenas escondo algumas surpresas entre as coisas que você já sabe fazer.”",
    commands: ["/mods", "/mods itens"],
    tip: "Os packs e o minimapa são opcionais quando dependem do cliente. No Lunar: Right Shift → Mods → Minimap.",
  },
};

function selectResident(key) {
  const resident = residents[key];
  if (!resident) return;
  document.querySelectorAll(".npc-card").forEach((card) => {
    const active = card.dataset.npc === key;
    card.classList.toggle("active", active);
    card.setAttribute("aria-pressed", active ? "true" : "false");
  });
  document.querySelector("#npc-name").textContent = resident.name;
  document.querySelector("#npc-role").textContent = resident.role;
  document.querySelector("#npc-quote").textContent = resident.quote;
  document.querySelector("#npc-tip").textContent = resident.tip;
  const commandBox = document.querySelector("#npc-commands");
  commandBox.replaceChildren(...resident.commands.map((command) => {
    const button = document.createElement("button");
    button.className = "dialogue-command";
    button.type = "button";
    button.dataset.copy = command;
    const code = document.createElement("code");
    code.textContent = command;
    button.appendChild(code);
    return button;
  }));
  bindCopyButtons(commandBox);
}

document.querySelectorAll(".npc-card").forEach((card) => {
  card.addEventListener("click", () => selectResident(card.dataset.npc));
  card.addEventListener("mouseenter", () => selectResident(card.dataset.npc));
  card.addEventListener("focus", () => selectResident(card.dataset.npc));
});
selectResident("eira");

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((item) => {
      const active = item === tab;
      item.classList.toggle("active", active);
      item.setAttribute("aria-selected", active ? "true" : "false");
    });
    document.querySelectorAll(".command-panel").forEach((panel) => panel.classList.toggle("active", panel.id === tab.dataset.tab));
  });
  tab.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
    const tabs = [...document.querySelectorAll(".tab")];
    const direction = ["ArrowRight", "ArrowDown"].includes(event.key) ? 1 : -1;
    const next = tabs[(tabs.indexOf(tab) + direction + tabs.length) % tabs.length];
    event.preventDefault();
    next.focus();
    next.click();
  });
});

if ("IntersectionObserver" in window && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("visible");
      observer.unobserve(entry.target);
    });
  }, { threshold: 0.08 });
  document.querySelectorAll(".reveal").forEach((element, index) => {
    element.style.transitionDelay = `${Math.min(index % 4, 3) * 45}ms`;
    observer.observe(element);
  });
} else {
  document.querySelectorAll(".reveal").forEach((element) => element.classList.add("visible"));
}
