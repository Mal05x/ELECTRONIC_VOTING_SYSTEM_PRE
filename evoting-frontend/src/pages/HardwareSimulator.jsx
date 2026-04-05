import { useState } from "react";
import { Ic, Spinner } from "../components/ui.jsx";
// Importing MOCK to artificially trigger dashboard updates for testing
import { MOCK } from "../api/mock.js";

export default function HardwareSimulator() {
  const [activeLog, setActiveLog] = useState([]);
  const [loadingStep, setLoadingStep] = useState(null);

  const addLog = (msg, type = "info") => {
    setActiveLog(prev => [{ time: new Date().toLocaleTimeString(), msg, type }, ...prev].slice(0, 8));
  };

  const simulateBoot = async () => {
    setLoadingStep("boot");
    addLog("Initializing ESP32-S3 Dual-Core...", "info");

    setTimeout(() => {
      addLog("Connecting to Wi-Fi & establishing mTLS...", "info");
      setTimeout(() => {
        addLog("TERMINAL ONLINE: TRM-SIM-01. Sending heartbeat.", "success");
        setLoadingStep(null);
      }, 1200);
    }, 800);
  };

  const simulateEnrollment = async () => {
    setLoadingStep("enroll");
    addLog("R307 Sensor: Capturing fingerprint minutiae...", "info");

    setTimeout(() => {
      addLog("PN5180: Waking up JCOP 4 Smart Card...", "info");
      setTimeout(() => {
        addLog("APDU INS_PERSONALIZE (0x10) sent successfully.", "info");
        addLog("SUCCESS: Biometrics & SCP03 keys burned to card.", "success");

        // Push a fake event to the Audit Log so your dashboard sees it
        MOCK.pushAuditEvent({
          sequenceNumber: Math.floor(Math.random() * 9000) + 1000,
          eventType: "ENROLLMENT_COMPLETED",
          actor: "TRM-SIM-01",
          eventData: "Simulated hardware enrollment successful",
          createdAt: new Date().toLocaleTimeString()
        });

        setLoadingStep(null);
      }, 1500);
    }, 1000);
  };

  const simulateVote = async () => {
    setLoadingStep("vote");
    addLog("Card inserted. Requesting Match-on-Card verification...", "info");

    setTimeout(() => {
      addLog("R307 Sensor: Fingerprint match confirmed 1:1.", "success");
      addLog("Card Status: Burning 'VOTED' flag to EEPROM.", "warning");

      setTimeout(() => {
        addLog("Transmitting anonymized AES-256 vote payload...", "info");
        addLog("SUCCESS: Vote cast and Merkle receipt received.", "success");

        // Push fake vote to tally and audit log
        MOCK.pushAuditEvent({
          sequenceNumber: Math.floor(Math.random() * 9000) + 1000,
          eventType: "VOTE_CAST",
          actor: "TRM-SIM-01",
          eventData: "TxID=SIM-" + Math.random().toString(36).substring(2, 8).toUpperCase() + " | card LOCKED",
          createdAt: new Date().toLocaleTimeString()
        });

        setLoadingStep(null);
      }, 1800);
    }, 1200);
  };

  return (
    <div className="min-h-screen bg-bg p-8 flex justify-center items-start">
      <div className="w-full max-w-2xl animate-fade-up">

        <div className="mb-6">
          <h1 className="text-2xl font-bold text-ink flex items-center gap-3">
            <Ic n="chip" s={24} c="#8B5CF6" />
            Hardware Simulator (ESP32-S3)
          </h1>
          <p className="text-sm text-sub mt-2">
            Developer tool to simulate physical hardware HTTP/WebSocket payloads without needing the actual circuitry.
          </p>
        </div>

        <div className="c-card p-6 mb-6 flex flex-col gap-4">
          <button
            onClick={simulateBoot}
            disabled={loadingStep !== null}
            className="btn btn-surface btn-lg justify-start hover:bg-blue-500/10 hover:border-blue-500/30">
            {loadingStep === "boot" ? <Spinner s={18} /> : <Ic n="refresh" s={18} c="#60A5FA" />}
            1. Simulate Terminal Boot & Heartbeat
          </button>

          <button
            onClick={simulateEnrollment}
            disabled={loadingStep !== null}
            className="btn btn-surface btn-lg justify-start hover:bg-amber-500/10 hover:border-amber-500/30">
            {loadingStep === "enroll" ? <Spinner s={18} /> : <Ic n="enroll" s={18} c="#FCD34D" />}
            2. Simulate Smart Card Biometric Burning (PN5180)
          </button>

          <button
            onClick={simulateVote}
            disabled={loadingStep !== null}
            className="btn btn-surface btn-lg justify-start hover:bg-green-500/10 hover:border-green-500/30">
            {loadingStep === "vote" ? <Spinner s={18} /> : <Ic n="vote" s={18} c="#34D399" />}
            3. Simulate Match-on-Card & Vote Cast
          </button>
        </div>

        {/* Console Output */}
        <div className="rounded-xl bg-[#0d0d1a] border border-border p-5 font-mono text-[11px] h-64 overflow-hidden flex flex-col justify-end relative shadow-inner">
          <div className="absolute top-3 left-4 text-[#4A4464] font-bold text-[10px] tracking-widest uppercase">
            Serial Monitor (UART 115200 baud)
          </div>
          <div className="flex flex-col gap-1.5 mt-6">
            {activeLog.slice().reverse().map((log, i) => (
              <div key={i} className={`
                ${log.type === "info" ? "text-purple-300" : ""}
                ${log.type === "success" ? "text-success" : ""}
                ${log.type === "warning" ? "text-warning" : ""}
              `}>
                <span className="text-muted mr-3">[{log.time}]</span>
                {log.msg}
              </div>
            ))}
            {activeLog.length === 0 && <div className="text-muted italic">Awaiting hardware commands...</div>}
          </div>
        </div>

      </div>
    </div>
  );
}