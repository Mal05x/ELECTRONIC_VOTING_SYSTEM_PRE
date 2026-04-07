import { useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext'; // Adjust path to your AuthContext

export default function OAuthCallback() {
  const navigate = useNavigate();
  const location = useLocation();
  const { loginWithToken } = useAuth(); // Assuming your context has a way to manually set the token

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const token = params.get("token");
    const role = params.get("role");
    const username = params.get("username");

    if (token) {
      // Save to localStorage/sessionStorage
      localStorage.setItem("evoting_jwt", token);
      localStorage.setItem("evoting_role", role);
      localStorage.setItem("evoting_username", username);

      // Redirect to the dashboard
      navigate("/dashboard");
      // Force a reload so the AuthContext picks up the new token
      window.location.reload();
    } else {
      navigate("/login?error=oauth_failed");
    }
  }, [location, navigate]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-900 text-white">
      <div className="animate-pulse">Authenticating with Google...</div>
    </div>
  );
}