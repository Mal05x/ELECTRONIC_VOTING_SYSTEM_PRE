import { Component } from "react";

/**
 * ErrorBoundary — catches React render errors in a subtree.
 * Without this, a crash in any page component blanks the entire screen.
 * Wrap individual views so only the broken tab shows an error, not the whole app.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error("[ErrorBoundary]", error, info?.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="p-10 flex flex-col items-center justify-center gap-4 text-center">
          <div className="w-14 h-14 rounded-2xl bg-red-500/10 border border-red-500/20
                          flex items-center justify-center text-2xl">
            ⚠
          </div>
          <div>
            <div className="text-base font-bold text-white mb-1">
              {this.props.title || "Something went wrong"}
            </div>
            <div className="text-xs text-sub max-w-sm leading-relaxed">
              {this.state.error?.message || "An unexpected error occurred in this view."}
            </div>
          </div>
          <button
            className="btn btn-surface btn-sm gap-2 mt-2"
            onClick={() => this.setState({ error: null })}>
            Try Again
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
