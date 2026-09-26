import type { GreetingLanguage } from "@prisma/client";

/** The default greeting scripts; the broker can edit them and record them in their own voice. */
export const DEFAULT_SCRIPTS: Record<GreetingLanguage, string> = {
  HINGLISH:
    "Hello! Main BrokerBuddy ki AI assistant hoon. Agent thode busy hain, toh aap mujhe bata dijiye ki aapko kaisa flat chahiye. Main unko aapki requirements bata dungi, aur woh aapko call back karenge.",
  HINDI:
    "नमस्ते! मैं BrokerBuddy की AI असिस्टेंट हूँ। एजेंट अभी थोड़े व्यस्त हैं, तो आप मुझे बता दीजिए कि आपको कैसा फ़्लैट चाहिए। मैं उन्हें आपकी ज़रूरतें बता दूँगी, और वे आपको वापस कॉल करेंगे।",
  ENGLISH:
    "Hello! I'm BrokerBuddy's AI assistant. The agent is a little busy right now, so please tell me what kind of flat you're looking for. I'll pass your requirements on, and they'll call you back.",
  MARATHI:
    "नमस्कार! मी BrokerBuddy ची AI असिस्टंट आहे. एजंट सध्या थोडे व्यस्त आहेत, तर तुम्हाला कसा फ्लॅट हवा आहे ते मला सांगा. मी तुमच्या गरजा त्यांना सांगेन, आणि ते तुम्हाला परत कॉल करतील.",
};

export type PromptKey =
  | "disclosure"
  | "openQuestion"
  | "transactionType"
  | "category"
  | "locations"
  | "budgetRent"
  | "budgetBuy"
  | "budget"
  | "name"
  | "extras"
  | "callbackConfirm"
  | "askNumber"
  | "invalidNumber"
  | "ackSkip"
  | "ackCallback"
  | "unclear"
  | "transfer"
  | "noTransfer"
  | "closingCallback"
  | "closingNoCallback"
  | "giveUp";

/**
 * Everything the assistant says after the greeting. It speaks as an AI assistant — it
 * never claims to be the broker. Placeholders: {last4}.
 */
export const PROMPTS: Record<GreetingLanguage, Record<PromptKey, string>> = {
  HINGLISH: {
    disclosure: "Bata doon, main ek AI assistant hoon, aur aapki baatein agent ke liye note ki ja rahi hain.",
    openQuestion: "Boliye, aapko kaisi property chahiye?",
    transactionType: "Aapko flat rent pe chahiye ya khareedna hai?",
    category: "Kitne BHK chahiye? Jaise 1 BHK, 2 BHK, ya studio.",
    locations: "Kaunse area mein dekh rahe hain?",
    budgetRent: "Mahine ka rent budget kitna hai?",
    budgetBuy: "Aapka total budget kitna hai?",
    budget: "Aapka budget kitna hai?",
    name: "Aapka naam kya hai?",
    extras: "Aur kuch zaroori hai? Jaise furnishing, parking, ya floor. Nahi toh 'bas' boliye.",
    callbackConfirm: "Kya agent aapko isi number pe call karein, jo {last4} pe khatam hota hai?",
    askNumber: "Kis number pe call karein? Agar call nahi chahiye toh 'nahi' boliye.",
    invalidNumber: "Yeh number samajh nahi aaya. Kripya das digit ka mobile number boliye.",
    ackSkip: "Koi baat nahi.",
    ackCallback: "Zaroor, agent aapko call back karenge.",
    unclear: "Maaf kijiye, main samajh nahi paayi.",
    transfer: "Main aapko agent se connect kar rahi hoon, kripya line pe rahiye.",
    noTransfer: "Abhi agent se connect nahi ho sakta, lekin woh aapko jaldi call back karenge.",
    closingCallback: "Dhanyavaad! Maine aapki requirement note kar li hai, agent aapko jaldi call karenge.",
    closingNoCallback: "Dhanyavaad! Maine aapki requirement note kar li hai.",
    giveUp: "Maaf kijiye, line saaf nahi hai. Agent aapko jaldi call back karenge. Dhanyavaad!",
  },
  HINDI: {
    disclosure: "बता दूँ, मैं एक AI असिस्टेंट हूँ, और आपकी बातें एजेंट के लिए नोट की जा रही हैं।",
    openQuestion: "बताइए, आपको कैसी प्रॉपर्टी चाहिए?",
    transactionType: "आपको फ़्लैट किराए पर चाहिए या ख़रीदना है?",
    category: "कितने BHK चाहिए? जैसे 1 BHK, 2 BHK या स्टूडियो।",
    locations: "किस इलाके में देख रहे हैं?",
    budgetRent: "महीने का किराया बजट कितना है?",
    budgetBuy: "आपका कुल बजट कितना है?",
    budget: "आपका बजट कितना है?",
    name: "आपका नाम क्या है?",
    extras: "और कुछ ज़रूरी है? जैसे फ़र्निशिंग, पार्किंग या फ़्लोर। नहीं तो 'बस' बोलिए।",
    callbackConfirm: "क्या एजेंट आपको इसी नंबर पर कॉल करें, जो {last4} पर ख़त्म होता है?",
    askNumber: "किस नंबर पर कॉल करें? अगर कॉल नहीं चाहिए तो 'नहीं' बोलिए।",
    invalidNumber: "यह नंबर समझ नहीं आया। कृपया दस अंकों का मोबाइल नंबर बोलिए।",
    ackSkip: "कोई बात नहीं।",
    ackCallback: "ज़रूर, एजेंट आपको वापस कॉल करेंगे।",
    unclear: "माफ़ कीजिए, मैं समझ नहीं पाई।",
    transfer: "मैं आपको एजेंट से जोड़ रही हूँ, कृपया लाइन पर रहिए।",
    noTransfer: "अभी एजेंट से जुड़ना संभव नहीं है, लेकिन वे आपको जल्दी वापस कॉल करेंगे।",
    closingCallback: "धन्यवाद! मैंने आपकी ज़रूरत नोट कर ली है, एजेंट आपको जल्दी कॉल करेंगे।",
    closingNoCallback: "धन्यवाद! मैंने आपकी ज़रूरत नोट कर ली है।",
    giveUp: "माफ़ कीजिए, लाइन साफ़ नहीं है। एजेंट आपको जल्दी वापस कॉल करेंगे। धन्यवाद!",
  },
  ENGLISH: {
    disclosure: "Just so you know, I'm an AI assistant, and I'm noting down what you say for the agent.",
    openQuestion: "Please go ahead — what kind of property are you looking for?",
    transactionType: "Are you looking to rent or to buy?",
    category: "How many bedrooms — for example 1 BHK, 2 BHK, or a studio?",
    locations: "Which areas are you looking in?",
    budgetRent: "What's your monthly rent budget?",
    budgetBuy: "What's your total budget?",
    budget: "What's your budget?",
    name: "May I have your name?",
    extras: "Anything else that matters — furnishing, parking, or floor? If not, just say 'that's all'.",
    callbackConfirm: "Should the agent call you back on this number, ending in {last4}?",
    askNumber: "Which number should they call? If you don't need a call, just say 'no'.",
    invalidNumber: "Sorry, I didn't get that number. Please say the ten-digit mobile number.",
    ackSkip: "No problem.",
    ackCallback: "Sure, the agent will call you back.",
    unclear: "Sorry, I didn't catch that.",
    transfer: "I'm connecting you to the agent now, please hold.",
    noTransfer: "I can't connect you to the agent right now, but they'll call you back soon.",
    closingCallback: "Thank you! I've noted your requirements, and the agent will call you soon.",
    closingNoCallback: "Thank you! I've noted your requirements.",
    giveUp: "Sorry, the line isn't clear. The agent will call you back soon. Thank you!",
  },
  MARATHI: {
    disclosure: "सांगू इच्छिते, मी एक AI असिस्टंट आहे, आणि तुम्ही जे सांगाल ते एजंटसाठी नोंदवले जात आहे.",
    openQuestion: "सांगा, तुम्हाला कशी प्रॉपर्टी हवी आहे?",
    transactionType: "तुम्हाला फ्लॅट भाड्याने हवा आहे की विकत घ्यायचा आहे?",
    category: "किती BHK हवा आहे? उदाहरणार्थ 1 BHK, 2 BHK किंवा स्टुडिओ.",
    locations: "कोणत्या भागात शोधत आहात?",
    budgetRent: "महिन्याचं भाड्याचं बजेट किती आहे?",
    budgetBuy: "तुमचं एकूण बजेट किती आहे?",
    budget: "तुमचं बजेट किती आहे?",
    name: "तुमचं नाव काय आहे?",
    extras: "अजून काही महत्त्वाचं आहे का? जसं फर्निशिंग, पार्किंग किंवा मजला. नसेल तर 'बस' म्हणा.",
    callbackConfirm: "एजंटने तुम्हाला याच नंबरवर कॉल करावा का, जो {last4} ने संपतो?",
    askNumber: "कोणत्या नंबरवर कॉल करावा? कॉल नको असेल तर 'नाही' म्हणा.",
    invalidNumber: "हा नंबर समजला नाही. कृपया दहा अंकी मोबाइल नंबर सांगा.",
    ackSkip: "काही हरकत नाही.",
    ackCallback: "नक्की, एजंट तुम्हाला परत कॉल करतील.",
    unclear: "माफ करा, मला समजलं नाही.",
    transfer: "मी तुम्हाला एजंटशी जोडत आहे, कृपया लाइनवर राहा.",
    noTransfer: "आत्ता एजंटशी जोडता येणार नाही, पण ते तुम्हाला लवकरच परत कॉल करतील.",
    closingCallback: "धन्यवाद! मी तुमची गरज नोंदवली आहे, एजंट तुम्हाला लवकरच कॉल करतील.",
    closingNoCallback: "धन्यवाद! मी तुमची गरज नोंदवली आहे.",
    giveUp: "माफ करा, लाइन स्पष्ट नाही. एजंट तुम्हाला लवकरच परत कॉल करतील. धन्यवाद!",
  },
};

export const say = (lang: GreetingLanguage, key: PromptKey, vars: Record<string, string> = {}) =>
  PROMPTS[lang][key].replace(/\{(\w+)\}/g, (_, k: string) => vars[k] ?? "");

/** Does a greeting script already tell the caller they're talking to an AI? */
export const scriptDisclosesAi = (script: string) => /\bAI\b|ए\.?आई|artificial/i.test(script);
