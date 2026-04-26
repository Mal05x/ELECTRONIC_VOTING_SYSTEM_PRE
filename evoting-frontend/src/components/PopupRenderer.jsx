import { useNotifications } from "../context/NotificationContext.jsx";
import { Ic } from "./ui.jsx";

export default function PopupRenderer() {
  const { popups, dismissPopup } = useNotifications();

  // If there are no active popups, don't render anything
  if (!popups || popups.length === 0) return null;

  return (
    // The container sits fixed in the top right.
    // pointer-events-none ensures you can still click the page underneath it
    <div className="fixed top-6 right-6 z-[9999] flex flex-col gap-3 pointer-events-none">

      {popups.map(popup => (
        <div
          key={popup.id}
          // pointer-events-auto re-enables clicking for the actual toast card
          className={`pointer-events-auto flex items-start gap-3 p-4 rounded-xl shadow-xl border bg-card w-80 animate-slide-in
            ${popup.type === 'error'   ? 'border-red-500/30' :
              popup.type === 'warning' ? 'border-yellow-500/30' :
                                         'border-purple-500/30'}`}
        >
           {/* Icon */}
           <div className="mt-0.5 flex-shrink-0">
             <Ic
               n={popup.type === 'error' ? 'warning' : popup.type === 'warning' ? 'warning' : 'check'}
               s={16}
               c={popup.type === 'error' ? '#F87171' : popup.type === 'warning' ? '#FCD34D' : '#A78BFA'}
             />
           </div>

           {/* Text Content */}
           <div className="flex-1 min-w-0">
             <div className="text-sm font-bold text-ink truncate">{popup.title}</div>
             <div className="text-xs text-sub mt-0.5 leading-relaxed line-clamp-2">{popup.body}</div>
             <div className="text-[10px] text-muted mt-1">{popup.time}</div>
           </div>

           {/* Manual Dismiss Button */}
           <button
             onClick={() => dismissPopup(popup.id)}
             className="text-muted hover:text-ink flex-shrink-0"
           >
             <Ic n="close" s={14} />
           </button>
        </div>
      ))}

    </div>
  );
}