(function () {
  "use strict";

  var form = document.getElementById("registration-form");
  var nextInput = document.getElementById("next-url");
  var relationshipFields = document.getElementById("relationship-fields");
  var relationshipSelect = document.getElementById("relationship");
  var relationshipOtherWrap = document.getElementById("relationship-other-wrap");
  var relationshipOtherInput = document.getElementById("relationship-other");

  var radios = form.querySelectorAll('input[name="registrant"]');

  // Allowed redirect hosts (add your partners or use * for POC)
  var ALLOWED_HOSTS = [
    "localhost",
    "127.0.0.1",
    "leadsmanagementweb.revenuewell.com",
    window.location.host,
  ];

  function getNextUrl() {
    var params = new URLSearchParams(window.location.search);
    var next = params.get("next") || params.get("redirect") || "";
    try {
      return next ? decodeURIComponent(next) : "";
    } catch (e) {
      return "";
    }
  }

  function isAllowedRedirect(url) {
    if (!url) return false;
    try {
      var u = new URL(url);
      return ALLOWED_HOSTS.indexOf(u.hostname) !== -1 || ALLOWED_HOSTS.indexOf("*") !== -1;
    } catch (e) {
      return false;
    }
  }

  function showRelationshipFields(show) {
    relationshipFields.hidden = !show;
    if (!show) {
      relationshipSelect.removeAttribute("required");
      relationshipOtherInput.removeAttribute("required");
    } else {
      relationshipSelect.setAttribute("required", "required");
      toggleRelationshipOther();
    }
  }

  function toggleRelationshipOther() {
    var isOther = relationshipSelect.value === "other";
    relationshipOtherWrap.hidden = !isOther;
    if (isOther) {
      relationshipOtherInput.setAttribute("required", "required");
    } else {
      relationshipOtherInput.removeAttribute("required");
      relationshipOtherInput.value = "";
    }
  }

  function toggleFromRegistrant() {
    var isAnother = form.querySelector('input[name="registrant"]:checked').value === "another";
    showRelationshipFields(isAnother);
  }

  // Bind: who is this for
  radios.forEach(function (r) {
    r.addEventListener("change", toggleFromRegistrant);
  });

  relationshipSelect.addEventListener("change", toggleRelationshipOther);

  // Initialize next URL and conditional state
  nextInput.value = getNextUrl();
  toggleFromRegistrant();

  // Block future dates for date of birth
  var dobInput = document.getElementById("dob");
  if (dobInput) {
    var today = new Date();
    var y = today.getFullYear();
    var m = String(today.getMonth() + 1).padStart(2, "0");
    var d = String(today.getDate()).padStart(2, "0");
    dobInput.setAttribute("max", y + "-" + m + "-" + d);
  }

  function showError(id, message) {
    var el = document.getElementById(id);
    if (el) {
      el.textContent = message || "";
      el.previousElementSibling && el.previousElementSibling.classList.toggle("has-error", !!message);
    }
  }

  function clearErrors() {
    form.querySelectorAll(".error-msg").forEach(function (el) {
      el.textContent = "";
    });
    form.querySelectorAll(".has-error").forEach(function (el) {
      el.classList.remove("has-error");
    });
  }

  function validate() {
    clearErrors();
    var valid = true;

    var firstName = document.getElementById("first-name");
    var lastName = document.getElementById("last-name");
    var dob = document.getElementById("dob");
    var phone = document.getElementById("phone");
    var email = document.getElementById("email");
    var confirmAccurate = document.getElementById("confirm-accurate");
    var agreePrivacy = document.getElementById("agree-privacy");

    if (!firstName.value.trim()) {
      showError("err-first-name", "First name is required.");
      valid = false;
    }
    if (!lastName.value.trim()) {
      showError("err-last-name", "Last name is required.");
      valid = false;
    }
    if (!dob.value) {
      showError("err-dob", "Date of birth is required.");
      valid = false;
    } else if (new Date(dob.value) > new Date()) {
      showError("err-dob", "Date of birth cannot be in the future.");
      valid = false;
    }
    if (!phone.value.trim()) {
      showError("err-phone", "Phone number is required.");
      valid = false;
    }
    if (!email.value.trim()) {
      showError("err-email", "Email is required.");
      valid = false;
    } else {
      var emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!emailRegex.test(email.value.trim())) {
        showError("err-email", "Please enter a valid email address.");
        valid = false;
      }
    }

    if (!confirmAccurate.checked) {
      showError("err-confirm-accurate", "Please confirm the information is accurate.");
      valid = false;
    }
    if (!agreePrivacy.checked) {
      showError("err-agree-privacy", "Please agree to the privacy policy / HIPAA notice.");
      valid = false;
    }

    if (!relationshipFields.hidden) {
      if (!relationshipSelect.value) {
        showError("err-relationship", "Please select relationship to patient.");
        valid = false;
      }
      if (relationshipSelect.value === "other" && !relationshipOtherInput.value.trim()) {
        showError("err-relationship-other", "Please specify the relationship.");
        valid = false;
      }
    }

    return valid;
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!validate()) return;

    var next = nextInput.value.trim();
    if (next && isAllowedRedirect(next)) {
      window.location.href = next;
    } else if (next) {
      alert("Redirect is not allowed to that URL. Please use a valid link.");
    } else {
      alert("Registration received. In production, data would be sent to your server.");
    }
  });
})();
