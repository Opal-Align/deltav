(function () {
  "use strict";

  var form = document.getElementById("registration-form");
  var nextInput = document.getElementById("next-url");

  var API_BASE = window.DELTAV_API_URL || "";
  var practiceId = new URLSearchParams(window.location.search).get("practice") || "";

  // ── Branding & patient — populated from API ───────────────────────────────
  var practiceName    = "";  // API variable: practice_name
  var practiceLogoUrl = "";  // API variable: practice_logo_url
  var patientFirstName = ""; // API variable: patient_first_name

  function applyBranding() {
    if (practiceName) {
      document.title = practiceName + " — Book an Appointment";
      var logo = document.getElementById("practice-logo");
      if (logo) logo.setAttribute("alt", practiceName);
    }
    if (practiceLogoUrl) {
      var logo = document.getElementById("practice-logo");
      if (logo) logo.setAttribute("src", practiceLogoUrl);
    }
    var heading = document.getElementById("welcome-heading");
    if (heading) {
      heading.textContent = patientFirstName
        ? "Welcome, " + patientFirstName + "!"
        : "Welcome!";
    }
  }

  // ── URL helpers ──────────────────────────────────────────────────────────
  function getNextUrl() {
    var params = new URLSearchParams(window.location.search);
    var next = params.get("next") || params.get("redirect") || "";
    try { return next ? decodeURIComponent(next) : ""; } catch (e) { return ""; }
  }

  // ── Calendar & slot picker ────────────────────────────────────────────────
  var calYear, calMonth, selectedDay;
  var selectedSlots = [];
  var MAX_SLOTS = 3;

  var MONTH_NAMES = ["January","February","March","April","May","June","July","August","September","October","November","December"];
  var DAY_ABBR    = ["Su","Mo","Tu","We","Th","Fr","Sa"];
  var DAY_SHORT   = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
  var MON_SHORT   = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];

  var TIME_SLOTS = ["AM", "PM"];

  function padZ(n) { return String(n).padStart(2, "0"); }
  function toDateStr(y, m, d) { return y + "-" + padZ(m) + "-" + padZ(d); }

  function dateStrDisplay(str) {
    var p = str.split("-");
    var d = new Date(parseInt(p[0]), parseInt(p[1]) - 1, parseInt(p[2]));
    return DAY_SHORT[d.getDay()] + ", " + MON_SHORT[d.getMonth()] + " " + d.getDate();
  }

  function findSlotIndex(key) {
    for (var i = 0; i < selectedSlots.length; i++) {
      if (selectedSlots[i].key === key) return i;
    }
    return -1;
  }

  function hasDateSlots(dateStr) {
    for (var i = 0; i < selectedSlots.length; i++) {
      if (selectedSlots[i].date === dateStr) return true;
    }
    return false;
  }

  function renderCalendar() {
    document.getElementById("cal-month-label").textContent = MONTH_NAMES[calMonth] + " " + calYear;
    var grid = document.getElementById("cal-grid");
    grid.innerHTML = "";

    DAY_ABBR.forEach(function (d) {
      var h = document.createElement("div");
      h.className = "cal-day-name";
      h.textContent = d;
      grid.appendChild(h);
    });

    var firstWeekday = new Date(calYear, calMonth, 1).getDay();
    var daysInMonth  = new Date(calYear, calMonth + 1, 0).getDate();
    var today = new Date(); today.setHours(0, 0, 0, 0);
    var maxDate = new Date(today); maxDate.setDate(maxDate.getDate() + 90);

    var nextMonthStart = new Date(calYear, calMonth + 1, 1);
    document.getElementById("cal-next").disabled = nextMonthStart > maxDate;

    for (var e = 0; e < firstWeekday; e++) {
      var empty = document.createElement("div");
      empty.className = "cal-cell empty";
      grid.appendChild(empty);
    }

    for (var day = 1; day <= daysInMonth; day++) {
      var cellDate = new Date(calYear, calMonth, day);
      var dateStr  = toDateStr(calYear, calMonth + 1, day);
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "cal-cell";
      btn.textContent = day;

      if (cellDate < today || cellDate > maxDate) {
        btn.disabled = true;
        btn.classList.add("past");
      } else {
        if (cellDate.getTime() === today.getTime()) btn.classList.add("today");
        if (dateStr === selectedDay) btn.classList.add("active");
        if (hasDateSlots(dateStr)) btn.classList.add("has-slots");
        btn.addEventListener("click", (function (ds) {
          return function () { selectDay(ds); };
        })(dateStr));
      }
      grid.appendChild(btn);
    }
  }

  function selectDay(dateStr) {
    selectedDay = dateStr;
    renderCalendar();
    renderTimeSlots(dateStr);
  }

  function renderTimeSlots(dateStr) {
    var wrap  = document.getElementById("cal-slots-wrap");
    var label = document.getElementById("cal-slots-date");
    var cont  = document.getElementById("cal-slots");

    label.textContent = dateStrDisplay(dateStr);
    cont.innerHTML = "";
    wrap.hidden = false;

    var atMax = selectedSlots.length >= MAX_SLOTS;

    TIME_SLOTS.forEach(function (time) {
      var key = dateStr + "|" + time;
      var isSelected = findSlotIndex(key) !== -1;
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "slot-btn" + (isSelected ? " selected" : "");
      btn.textContent = time;
      if (!isSelected && atMax) { btn.disabled = true; }
      btn.addEventListener("click", function () { toggleSlot(dateStr, time, key); });
      cont.appendChild(btn);
    });
  }

  function toggleSlot(dateStr, time, key) {
    var idx = findSlotIndex(key);
    if (idx !== -1) {
      selectedSlots.splice(idx, 1);
    } else if (selectedSlots.length < MAX_SLOTS) {
      selectedSlots.push({ date: dateStr, time: time, key: key });
    }
    renderTimeSlots(dateStr);
    renderCalendar();
    renderSelectedSlots();
  }

  function renderSelectedSlots() {
    var wrap  = document.getElementById("selected-slots");
    var chips = document.getElementById("slot-chips");
    if (selectedSlots.length === 0) { wrap.hidden = true; return; }
    wrap.hidden = false;
    chips.innerHTML = "";
    selectedSlots.forEach(function (s) {
      var chip = document.createElement("div");
      chip.className = "slot-chip";
      var lbl = document.createElement("span");
      lbl.textContent = dateStrDisplay(s.date) + " · " + s.time;
      var removeBtn = document.createElement("button");
      removeBtn.type = "button";
      removeBtn.className = "chip-remove";
      removeBtn.setAttribute("aria-label", "Remove slot");
      removeBtn.textContent = "×";
      removeBtn.addEventListener("click", (function (ds, t, k) {
        return function () { toggleSlot(ds, t, k); };
      })(s.date, s.time, s.key));
      chip.appendChild(lbl);
      chip.appendChild(removeBtn);
      chips.appendChild(chip);
    });
  }

  document.getElementById("cal-prev").addEventListener("click", function () {
    calMonth--;
    if (calMonth < 0) { calMonth = 11; calYear--; }
    clearDayIfOutOfMonth();
    renderCalendar();
  });

  document.getElementById("cal-next").addEventListener("click", function () {
    calMonth++;
    if (calMonth > 11) { calMonth = 0; calYear++; }
    clearDayIfOutOfMonth();
    renderCalendar();
  });

  function clearDayIfOutOfMonth() {
    if (!selectedDay) return;
    var p = selectedDay.split("-");
    if (parseInt(p[0]) !== calYear || parseInt(p[1]) - 1 !== calMonth) {
      selectedDay = null;
      document.getElementById("cal-slots-wrap").hidden = true;
    }
  }

  // ── Validation ───────────────────────────────────────────────────────────
  function showError(id, message) {
    var el = document.getElementById(id);
    if (!el) return;
    el.textContent = message || "";
    if (el.previousElementSibling) {
      el.previousElementSibling.classList.toggle("has-error", !!message);
    }
  }

  function clearErrors() {
    form.querySelectorAll(".error-msg").forEach(function (el) { el.textContent = ""; });
    form.querySelectorAll(".has-error").forEach(function (el) { el.classList.remove("has-error"); });
  }

  function validate() {
    clearErrors();
    var valid = true;

    var ap = document.getElementById("agree-privacy");
    if (!ap.checked) { showError("err-agree-privacy", "Please agree to the privacy policy / HIPAA notice."); valid = false; }

    if (!valid) {
      var firstError = form.querySelector(".error-msg:not(:empty)");
      if (firstError) {
        firstError.scrollIntoView({ behavior: "smooth", block: "center" });
        var field = firstError.previousElementSibling;
        if (field && field.focus) field.focus();
      }
    }

    return valid;
  }

  // ── Submit + OTP verification ─────────────────────────────────────────────
  var pendingPayload = null;
  var sessionId = null;
  var otpTimerId = null;
  var resendLocked = false;

  var otpModal = document.getElementById("otp-modal");
  var mobileInput = document.getElementById("mobile-number");
  var otpInput = document.getElementById("otp-code");
  var otpSendBtn = document.getElementById("otp-send-btn");
  var otpVerifyBtn = document.getElementById("otp-verify-btn");
  var otpResendBtn = document.getElementById("otp-resend-btn");
  var otpCancelBtn = document.getElementById("otp-cancel-btn");
  var otpStepSend = document.getElementById("otp-step-send");
  var otpStepVerify = document.getElementById("otp-step-verify");
  var otpStepSuccess = document.getElementById("otp-step-success");
  var otpModalTitle = document.getElementById("otp-modal-title");
  var otpModalSub = document.querySelector(".otp-modal-sub");
  var otpTimerWrap = document.getElementById("otp-timer");
  var otpTimerValue = document.getElementById("otp-timer-value");
  var otpPocHint = document.getElementById("otp-poc-hint");

  function authHeaders() {
    return {
      "Content-Type": "application/json",
      "X-Registration-Token": window.DELTAV_TOKEN || ""
    };
  }

  function showOtpError(id, message) {
    var el = document.getElementById(id);
    if (!el) return;
    el.textContent = message || "";
  }

  function normalizeMobileDigits(value) {
    var digits = (value || "").replace(/\D/g, "");
    if (digits.length === 11 && digits.charAt(0) === "1") {
      digits = digits.substring(1);
    }
    return digits.slice(0, 10);
  }

  function clearOtpErrors() {
    showOtpError("err-mobile", "");
    showOtpError("err-otp", "");
  }

  function stopOtpTimer() {
    if (otpTimerId) {
      clearInterval(otpTimerId);
      otpTimerId = null;
    }
    if (otpTimerWrap) otpTimerWrap.hidden = true;
  }

  function setResendState(enabled, secondsRemaining) {
    if (!otpResendBtn) return;
    var canEnable = enabled && !resendLocked;
    otpResendBtn.disabled = !canEnable;
    if (!canEnable && secondsRemaining > 0) {
      otpResendBtn.textContent = "Resend OTP in " + secondsRemaining + "s";
    } else {
      otpResendBtn.textContent = "Resend OTP";
    }
  }

  function lockResend(seconds) {
    resendLocked = true;
    setResendState(false, seconds);
  }

  function unlockResend() {
    resendLocked = false;
    setResendState(true, 0);
  }

  function startOtpTimer(seconds) {
    stopOtpTimer();
    var remaining = seconds;
    lockResend(remaining);
    if (otpTimerValue) otpTimerValue.textContent = String(remaining);
    if (otpTimerWrap) otpTimerWrap.hidden = false;
    otpTimerId = setInterval(function () {
      remaining -= 1;
      if (otpTimerValue) otpTimerValue.textContent = String(Math.max(0, remaining));
      if (remaining > 0) {
        lockResend(remaining);
      } else {
        stopOtpTimer();
        unlockResend();
        showOtpError("err-otp", "OTP expired. Please resend a new code.");
      }
    }, 1000);
  }

  function resetOtpModalSteps() {
    if (otpModalTitle) otpModalTitle.hidden = false;
    if (otpModalSub) otpModalSub.hidden = false;
    if (otpStepSend) otpStepSend.hidden = false;
    if (otpStepVerify) otpStepVerify.hidden = true;
    if (otpStepSuccess) otpStepSuccess.hidden = true;
    if (otpCancelBtn) otpCancelBtn.hidden = false;
  }

  function openOtpModal() {
    clearOtpErrors();
    stopOtpTimer();
    resendLocked = false;
    sessionId = null;
    resetOtpModalSteps();
    if (otpResendBtn) {
      otpResendBtn.disabled = true;
      otpResendBtn.textContent = "Resend OTP";
    }
    if (otpInput) otpInput.value = "";
    if (otpPocHint) {
      otpPocHint.hidden = true;
      otpPocHint.textContent = "";
    }
    if (otpModal) {
      otpModal.hidden = false;
      otpModal.setAttribute("aria-hidden", "false");
    }
    if (mobileInput) mobileInput.focus();
  }

  function showRegistrationSuccess() {
    stopOtpTimer();
    if (otpModalTitle) otpModalTitle.hidden = true;
    if (otpModalSub) otpModalSub.hidden = true;
    if (otpStepSend) otpStepSend.hidden = true;
    if (otpStepVerify) otpStepVerify.hidden = true;
    if (otpStepSuccess) otpStepSuccess.hidden = false;
    if (otpCancelBtn) otpCancelBtn.hidden = true;
    if (otpModal) {
      otpModal.hidden = false;
      otpModal.setAttribute("aria-hidden", "false");
    }
  }

  function closeOtpModal() {
    stopOtpTimer();
    resetOtpModalSteps();
    if (otpModal) {
      otpModal.hidden = true;
      otpModal.setAttribute("aria-hidden", "true");
    }
  }

  function buildPayload() {
    var payload = {
      practice:      practiceId,
      agree_privacy: document.getElementById("agree-privacy").checked,
    };

    if (selectedSlots.length > 0) {
      payload.preferred_slots = selectedSlots.map(function (s) { return s.date + " " + s.time; });
    }

    var comments = document.getElementById("comments").value.trim();
    if (comments) payload.comments = comments;

    if (window.DELTAV_TOKEN) {
      payload.registration_token = window.DELTAV_TOKEN;
    }

    return payload;
  }

  function submitRegistration(payload) {
    var submitBtn = form.querySelector('button[type="submit"]');
    submitBtn.disabled = true;
    submitBtn.textContent = "Submitting…";

    return fetch(API_BASE + "/api/register", {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify(payload)
    })
      .then(function (res) {
        return res.json().then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (result) {
        if (result.status === 201) {
          window.dataLayer = window.dataLayer || [];
          window.dataLayer.push({ event: "booking_request" });
          showRegistrationSuccess();
          form.reset();
        } else if (result.status === 400 && result.data.errors) {
          alert("Validation errors:\n" + result.data.errors.join("\n"));
        } else {
          alert("Something went wrong. Please try again.");
        }
      })
      .catch(function () {
        alert("Network error. Please check your connection and try again.");
      })
      .finally(function () {
        if (otpStepSuccess && !otpStepSuccess.hidden) return;
        submitBtn.disabled = false;
        submitBtn.innerHTML = 'Continue <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 8h10M9 4l4 4-4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
      });
  }

  function sendOtp() {
    clearOtpErrors();
    var mobile = normalizeMobileDigits(mobileInput ? mobileInput.value : "");
    if (mobile.length !== 10) {
      showOtpError("err-mobile", "Please enter a valid 10-digit mobile number.");
      return;
    }

    var isResend = otpStepVerify && !otpStepVerify.hidden;
    if (isResend && otpResendBtn && otpResendBtn.disabled) {
      return;
    }

    var sendSucceeded = false;
    var payload = { practice: practiceId, mobile: mobile };
    if (isResend && sessionId) {
      payload = { session_id: sessionId };
    }

    if (otpResendBtn) otpResendBtn.disabled = true;
    if (isResend) {
      otpResendBtn.textContent = "Sending…";
    } else if (otpSendBtn) {
      otpSendBtn.disabled = true;
      otpSendBtn.textContent = "Sending…";
    }

    fetch((API_BASE || window.location.origin) + "/api/otp/send", {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify(payload)
    })
      .then(function (res) {
        return res.json().then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (result) {
        if (result.status === 429 && result.data.error === "resend_throttled") {
          showOtpError("err-otp", "Please wait " + (result.data.retry_after_seconds || 60) + "s before resending.");
          return;
        }
        if (result.status === 429 && result.data.error === "send_limit_reached") {
          showOtpError("err-otp", "Maximum resend limit reached. Please refresh and try again.");
          return;
        }
        if (result.status !== 200 || !result.data.success) {
          showOtpError(isResend ? "err-otp" : "err-mobile",
            result.data.error === "invalid_practice"
              ? "Invalid practice. Please use the registration link provided by your practice."
              : result.data.error === "invalid_mobile"
              ? "Please enter a valid mobile number."
              : result.data.error === "sms_send_failed"
              ? (result.data.message || "Could not send OTP. Please try again.")
              : "Could not send OTP. Please try again.");
          return;
        }

        sessionId = result.data.session_id;
        if (otpStepSend) otpStepSend.hidden = true;
        if (otpStepVerify) otpStepVerify.hidden = false;
        if (otpInput) {
          otpInput.value = "";
          otpInput.focus();
        }
        sendSucceeded = true;
        showOtpError("err-otp", "");
        startOtpTimer(result.data.expires_in_seconds || 60);
        if (result.data.poc_otp && otpPocHint) {
          otpPocHint.hidden = false;
          otpPocHint.textContent = "Development — your code is " + result.data.poc_otp;
        }
      })
      .catch(function () {
        showOtpError(isResend ? "err-otp" : "err-mobile", "Network error. Please try again.");
      })
      .finally(function () {
        if (isResend) {
          if (!sendSucceeded && !otpTimerId) unlockResend();
        } else if (otpSendBtn) {
          otpSendBtn.disabled = false;
          otpSendBtn.textContent = "Send OTP";
        }
        if (otpResendBtn) otpResendBtn.textContent = "Resend OTP";
      });
  }

  function verifyOtp() {
    clearOtpErrors();
    var otp = (otpInput ? otpInput.value : "").trim();

    if (!sessionId) {
      showOtpError("err-otp", "Session expired. Please start again.");
      return;
    }
    if (!/^\d{6}$/.test(otp)) {
      showOtpError("err-otp", "Please enter the 6-digit OTP.");
      return;
    }

    otpVerifyBtn.disabled = true;
    otpVerifyBtn.textContent = "Verifying…";

    fetch((API_BASE || window.location.origin) + "/api/otp/verify", {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify({ session_id: sessionId, otp: otp })
    })
      .then(function (res) {
        return res.json().then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (result) {
        if (result.data.refresh_required || result.data.error === "too_many_attempts") {
          alert("Too many incorrect attempts. The page will refresh.");
          window.location.reload();
          return;
        }
        if (result.data.verified) {
          stopOtpTimer();
          if (pendingPayload) {
            pendingPayload.session_id = sessionId;
            return submitRegistration(pendingPayload);
          }
          return;
        }
        if (result.data.error === "otp_expired") {
          showOtpError("err-otp", "OTP expired. Please resend a new code.");
          return;
        }
        var remaining = result.data.attempts_remaining;
        showOtpError("err-otp", typeof remaining === "number"
          ? "Incorrect OTP. " + remaining + " attempt(s) remaining."
          : "Incorrect OTP. Please try again.");
      })
      .catch(function () {
        showOtpError("err-otp", "Network error. Please try again.");
      })
      .finally(function () {
        otpVerifyBtn.disabled = false;
        otpVerifyBtn.textContent = "Verify & Continue";
      });
  }

  if (otpSendBtn) otpSendBtn.addEventListener("click", sendOtp);
  if (otpResendBtn) otpResendBtn.addEventListener("click", sendOtp);
  if (otpVerifyBtn) otpVerifyBtn.addEventListener("click", verifyOtp);
  if (otpCancelBtn) otpCancelBtn.addEventListener("click", closeOtpModal);

  if (mobileInput) {
    mobileInput.addEventListener("input", function () {
      var digits = normalizeMobileDigits(mobileInput.value);
      if (digits.length <= 3) {
        mobileInput.value = digits;
      } else if (digits.length <= 6) {
        mobileInput.value = "(" + digits.slice(0, 3) + ") " + digits.slice(3);
      } else {
        mobileInput.value = "(" + digits.slice(0, 3) + ") " + digits.slice(3, 6) + "-" + digits.slice(6);
      }
    });
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!validate()) return;
    pendingPayload = buildPayload();
    openOtpModal();
  });

  // ── Init ──────────────────────────────────────────────────────────────────
  nextInput.value = getNextUrl();

  var now = new Date();
  calYear  = now.getFullYear();
  calMonth = now.getMonth();
  selectedDay = null;
  renderCalendar();

})();
