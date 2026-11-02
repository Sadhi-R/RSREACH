import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import {
  CustomerDashboardPage,
  CustomerLoginPage,
  CustomerRegisterPage,
  HomePage,
  PlatformDashboardPage,
  RequireCustomer,
  RequireStaff,
  StaffLoginPage
} from "./pages";

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/app/register" element={<CustomerRegisterPage />} />
        <Route path="/app/login" element={<CustomerLoginPage />} />
        <Route
          path="/app"
          element={
            <RequireCustomer>
              <CustomerDashboardPage />
            </RequireCustomer>
          }
        />
        <Route path="/platform/login" element={<StaffLoginPage />} />
        <Route
          path="/platform"
          element={
            <RequireStaff>
              <PlatformDashboardPage />
            </RequireStaff>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
