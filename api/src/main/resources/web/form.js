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

  // Set this to your Azure Functions URL in production, e.g. "https://deltav-api.azurewebsites.net"
  var API_BASE = window.DELTAV_API_URL || "";

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!validate()) return;

    var registrantValue = form.querySelector('input[name="registrant"]:checked').value;
    var payload = {
      registrant: registrantValue,
      first_name: document.getElementById("first-name").value.trim(),
      last_name: document.getElementById("last-name").value.trim(),
      dob: document.getElementById("dob").value,
      phone: document.getElementById("phone").value.trim(),
      email: document.getElementById("email").value.trim(),
      confirm_accurate: document.getElementById("confirm-accurate").checked,
      agree_privacy: document.getElementById("agree-privacy").checked,
      redirect_url: nextInput.value.trim()
    };

    if (registrantValue === "another") {
      payload.relationship = relationshipSelect.value;
      if (relationshipSelect.value === "other") {
        payload.relationship_other = relationshipOtherInput.value.trim();
      }
    }

    var submitBtn = form.querySelector('button[type="submit"]');
    submitBtn.disabled = true;
    submitBtn.textContent = "Submitting...";

    fetch(API_BASE + "/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    })
      .then(function (res) {
        return res.json().then(function (data) {
          return { status: res.status, data: data };
        });
      })
      .then(function (result) {
        if (result.status >= 200 && result.status < 300) {
          var redirect = (result.data && result.data.redirect_url) || nextInput.value || "";
          if (redirect && isAllowedRedirect(redirect)) {
            window.location.href = redirect;
          } else {
            alert("Registration submitted successfully. We'll be in touch soon.");
            submitBtn.disabled = false;
            submitBtn.textContent = "Continue";
          }
        } else {
          var errors = (result.data && (result.data.errors || result.data.error)) || "Unknown error";
          if (Array.isArray(errors)) {
            alert("Please fix the following errors:\n\n" + errors.join("\n"));
          } else if (typeof errors === "string") {
            alert(errors);
          } else {
            alert("There was a problem submitting the form.");
          }
          submitBtn.disabled = false;
          submitBtn.textContent = "Continue";
        }
      })
      .catch(function (err) {
        console.error(err);
        alert("Network or server error. Please try again later.");
        submitBtn.disabled = false;
        submitBtn.textContent = "Continue";
      });
  });
})();
